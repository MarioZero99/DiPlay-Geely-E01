package com.shilapi.xcertplay.instrument.fly

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch

/**
 * Draws the centre screen's picture onto two windows from one texture.
 *
 * The hardware decoder writes into a [SurfaceTexture]; one GL thread copies that texture onto the
 * centre Surface and the cluster Surface. Nothing is decoded twice and nothing is read back. The
 * centre Surface must be free of the decoder before EGL takes a window on it, and the decoder must
 * be pointed back at it before this class is closed — [FlyOutputHandoff] owns that order.
 *
 * The thread prepares a Looper before any window is built: a SurfaceTexture with no listener loses
 * the frames the decoder keeps producing, and the decoder's output queue then fills and the centre
 * screen stops.
 */
internal class FlyTextureMirror(private val log: (String) -> Unit) {
    private val mutex = Any()
    private val ready = CountDownLatch(1)
    private var handler: Handler? = null
    private var started = false
    private var closed = false
    @Volatile private var closing = false

    private var display: EGLDisplay? = null
    private var context: EGLContext? = null
    private var centerEgl: EGLSurface? = null
    private var clusterEgl: EGLSurface? = null
    private var textureId = 0
    private var program = 0
    private var positionAttr = -1
    private var texCoordAttr = -1
    private var texMatrixUniform = -1
    private var samplerUniform = -1
    private var surfaceTexture: SurfaceTexture? = null
    private var textureSurface: Surface? = null
    private var centerWidth = 0
    private var centerHeight = 0
    private var clusterWidth = 0
    private var clusterHeight = 0
    private var onFrame: (() -> Unit)? = null
    private var onBroken: (() -> Unit)? = null
    private var frameFailures = 0
    private var brokenReported = false
    private var frameLogged = false
    private var lastSlowLogMs = 0L
    private var framePending = false
    private val texMatrix = FloatArray(16)

    private val quadPositions: FloatBuffer = directFloats(
        floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f),
    )
    private val quadTexCoords: FloatBuffer = directFloats(
        floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f),
    )

    private val thread = Thread(::runGl, "cluster-fly-mirror").apply {
        isDaemon = true
        start()
    }

    fun open(
        center: Surface,
        cluster: Surface,
        onTexture: (Surface) -> Unit,
        onFrame: () -> Unit,
        onBroken: () -> Unit,
    ) {
        ready.await()
        val main = synchronized(mutex) {
            if (closed) null else handler
        } ?: run {
            onBroken()
            return
        }
        synchronized(mutex) { started = true }
        main.post { openOnGl(center, cluster, onTexture, onFrame, onBroken) }
    }

    fun close() {
        val main = synchronized(mutex) {
            if (closed) return
            closed = true
            started = false
            handler
        }
        closing = true
        main?.post { Looper.myLooper()?.quit() }
        // The windows and the context die on the GL thread; the caller waits for that before the
        // surfaces are torn down around it. Bounded: a stuck swap must not hang the main thread.
        runCatching { thread.join(CLOSE_JOIN_MS) }
    }

    private fun runGl() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
        Looper.prepare()
        synchronized(mutex) { handler = Handler(Looper.myLooper()!!) }
        ready.countDown()
        try {
            Looper.loop()
        } finally {
            releaseGl()
            synchronized(mutex) { handler = null }
        }
    }

    private fun openOnGl(
        center: Surface,
        cluster: Surface,
        onTexture: (Surface) -> Unit,
        onFrame: () -> Unit,
        onBroken: () -> Unit,
    ) {
        if (closing) return
        if (!center.isValid || !cluster.isValid) {
            fail("surface", onBroken)
            return
        }
        val eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            fail("egl", onBroken)
            return
        }
        display = eglDisplay
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            fail("egl", onBroken)
            return
        }
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val configCount = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attributes, 0, configs, 0, 1, configCount, 0) ||
            configCount[0] <= 0
        ) {
            fail("egl", onBroken)
            return
        }
        val config = configs[0] ?: run {
            fail("egl", onBroken)
            return
        }
        val contextAttributes = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        val eglContext = EGL14.eglCreateContext(
            eglDisplay,
            config,
            EGL14.EGL_NO_CONTEXT,
            contextAttributes,
            0,
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            fail("egl", onBroken)
            return
        }
        context = eglContext
        // The centre window first: the shader program needs a current context to compile on, and
        // there is no pbuffer in this setup.
        val centerWindow = eglWindow(eglDisplay, config, center)
        if (centerWindow == null) {
            fail("window", onBroken)
            return
        }
        centerEgl = centerWindow
        val centerSize = windowSize(eglDisplay, centerWindow)
        centerWidth = centerSize[0]
        centerHeight = centerSize[1]
        val clusterWindow = eglWindow(eglDisplay, config, cluster)
        if (clusterWindow == null) {
            fail("window", onBroken)
            return
        }
        clusterEgl = clusterWindow
        val clusterSize = windowSize(eglDisplay, clusterWindow)
        clusterWidth = clusterSize[0]
        clusterHeight = clusterSize[1]
        if (!EGL14.eglMakeCurrent(eglDisplay, centerWindow, centerWindow, eglContext)) {
            fail("egl", onBroken)
            return
        }
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        if (!buildProgram()) {
            fail("egl", onBroken)
            return
        }
        val texture = SurfaceTexture(textureId)
        texture.setDefaultBufferSize(FRAME_WIDTH, FRAME_HEIGHT)
        texture.setOnFrameAvailableListener({ onFrameAvailable() }, handler)
        surfaceTexture = texture
        this.onFrame = onFrame
        this.onBroken = onBroken
        val surface = Surface(texture)
        textureSurface = surface
        log(FlyMirrorLog.mirror())
        // The handoff is waiting only for the texture. The frame loop begins as soon as the decoder
        // starts writing; this call must not wait for a frame.
        onTexture(surface)
    }

    private fun onFrameAvailable() {
        if (closing) return
        if (framePending) return
        framePending = true
        handler?.post { drawFrame() }
    }

    private fun drawFrame() {
        framePending = false
        if (closing) return
        val eglDisplay = display ?: return
        val eglContext = context ?: return
        val centerWindow = centerEgl ?: return
        val clusterWindow = clusterEgl ?: return
        val texture = surfaceTexture ?: return
        val startedNs = System.nanoTime()
        val centerCurrent = EGL14.eglMakeCurrent(eglDisplay, centerWindow, centerWindow, eglContext)
        if (!centerCurrent &&
            !EGL14.eglMakeCurrent(eglDisplay, clusterWindow, clusterWindow, eglContext)
        ) {
            countFrameFailure()
            return
        }
        // The frame is consumed even when only the cluster window is current: an unconsumed
        // texture fills the decoder's output queue and stops the centre picture with it.
        texture.updateTexImage()
        texture.getTransformMatrix(texMatrix)
        if (centerCurrent) {
            draw(centerWidth, centerHeight)
            EGL14.eglSwapBuffers(eglDisplay, centerWindow)
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, clusterWindow, clusterWindow, eglContext)) {
            countFrameFailure()
            return
        }
        draw(clusterWidth, clusterHeight)
        val shown = EGL14.eglSwapBuffers(eglDisplay, clusterWindow)
        if (shown) {
            frameFailures = 0
        } else {
            countFrameFailure()
        }
        if (!frameLogged && shown) {
            frameLogged = true
            log(FlyMirrorLog.frame())
            onFrame?.invoke()
        }
        val spentMs = (System.nanoTime() - startedNs) / 1_000_000L
        if (spentMs > SLOW_MS) {
            val nowMs = System.nanoTime() / 1_000_000L
            if (lastSlowLogMs == 0L || nowMs - lastSlowLogMs >= SLOW_LOG_GAP_MS) {
                lastSlowLogMs = nowMs
                log("fly.display paint slow $spentMs")
            }
        }
    }

    /** Three frames in a row with no usable window mean the mirror is gone, not that one frame was. */
    private fun countFrameFailure() {
        frameFailures++
        if (frameFailures < MAX_FRAME_FAILURES || brokenReported) return
        brokenReported = true
        onBroken?.invoke()
    }

    private fun draw(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        quadPositions.position(0)
        GLES20.glEnableVertexAttribArray(positionAttr)
        GLES20.glVertexAttribPointer(positionAttr, 2, GLES20.GL_FLOAT, false, 0, quadPositions)
        quadTexCoords.position(0)
        GLES20.glEnableVertexAttribArray(texCoordAttr)
        GLES20.glVertexAttribPointer(texCoordAttr, 2, GLES20.GL_FLOAT, false, 0, quadTexCoords)
        GLES20.glUniformMatrix4fv(texMatrixUniform, 1, false, texMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(samplerUniform, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionAttr)
        GLES20.glDisableVertexAttribArray(texCoordAttr)
    }

    private fun buildProgram(): Boolean {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        if (vertex == 0 || fragment == 0) {
            if (vertex != 0) GLES20.glDeleteShader(vertex)
            if (fragment != 0) GLES20.glDeleteShader(fragment)
            return false
        }
        val id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, vertex)
        GLES20.glAttachShader(id, fragment)
        GLES20.glLinkProgram(id)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            GLES20.glDeleteProgram(id)
            return false
        }
        program = id
        positionAttr = GLES20.glGetAttribLocation(id, "aPosition")
        texCoordAttr = GLES20.glGetAttribLocation(id, "aTextureCoord")
        texMatrixUniform = GLES20.glGetUniformLocation(id, "uTexMatrix")
        samplerUniform = GLES20.glGetUniformLocation(id, "sTexture")
        return positionAttr >= 0 && texCoordAttr >= 0 &&
            texMatrixUniform >= 0 && samplerUniform >= 0
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) return 0
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun eglWindow(eglDisplay: EGLDisplay, config: EGLConfig, surface: Surface): EGLSurface? {
        val window = EGL14.eglCreateWindowSurface(
            eglDisplay,
            config,
            surface,
            intArrayOf(EGL14.EGL_NONE),
            0,
        )
        return if (window == EGL14.EGL_NO_SURFACE) null else window
    }

    private fun windowSize(eglDisplay: EGLDisplay, window: EGLSurface): IntArray {
        val width = IntArray(1)
        val height = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, window, EGL14.EGL_WIDTH, width, 0)
        EGL14.eglQuerySurface(eglDisplay, window, EGL14.EGL_HEIGHT, height, 0)
        return intArrayOf(width[0], height[0])
    }

    private fun fail(reason: String, onBroken: () -> Unit) {
        log(FlyMirrorLog.failed(reason))
        releaseGl()
        onBroken()
    }

    /** Destroys everything this open built. Safe on a half-built open, and safe twice. */
    private fun releaseGl() {
        val eglDisplay = display
        val eglContext = context
        if (eglDisplay != null) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            centerEgl?.let { EGL14.eglDestroySurface(eglDisplay, it) }
            clusterEgl?.let { EGL14.eglDestroySurface(eglDisplay, it) }
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        centerEgl = null
        clusterEgl = null
        if (eglDisplay != null && eglContext != null) {
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        context = null
        display = null
        textureSurface?.release()
        textureSurface = null
        surfaceTexture?.release()
        surfaceTexture = null
        onFrame = null
        onBroken = null
    }

    private companion object {
        const val FRAME_WIDTH = 1920
        const val FRAME_HEIGHT = 720
        const val SLOW_MS = 32L
        const val SLOW_LOG_GAP_MS = 5_000L
        const val CLOSE_JOIN_MS = 2_000L
        const val MAX_FRAME_FAILURES = 3

        fun directFloats(values: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(values)
                    position(0)
                }

        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = aPosition;
                vTextureCoord = (uTexMatrix * aTextureCoord).xy;
            }
        """

        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
        """
    }
}
