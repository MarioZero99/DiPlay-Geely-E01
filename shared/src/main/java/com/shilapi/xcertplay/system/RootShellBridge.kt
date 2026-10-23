package com.shilapi.xcertplay.system

import android.os.IBinder
import android.os.Parcel
import com.shilapi.xcertplay.DiagLog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Executes commands as uid 0 (root) through the head unit's `ExtraUtilsService`.
 *
 * The service is a vendor Binder exposed by `com.neusoft.alfus.os.IExtraUtilsService`.
 * The command line has to fit in 220 bytes, including the exit-code wrapper.
 * If unavailable (e.g. on generic phones, AVDs, or unit test runners), operations return false.
 * A call that is still inside the service when [ROOT_COMMAND_TIMEOUT_MILLIS] elapses returns false
 * and blocks the next root command until that one returns, so a stuck shell is not followed by another.
 */
object RootShellBridge {
    private const val TAG = "RootShellBridge"
    private const val SERVICE_NAME = "ExtraUtilsService"
    private const val DESCRIPTOR = "com.neusoft.alfus.os.IExtraUtilsService"
    private const val TRANSACTION_GET_MESSAGE = 6
    private const val EVENT_SHELL = 8
    private const val ACTION_EXEC = 0
    private const val RC_MARKER = "__RC:"
    private const val MAX_COMMAND_BYTES = 220
    const val ROOT_COMMAND_TIMEOUT_MILLIS = 1_500L

    private val SAFE_PATH = Regex("^/[A-Za-z0-9_./-]+$")
    private val inFlight = AtomicBoolean(false)

    fun isAvailable(): Boolean = findExtraUtilsService() != null

    internal fun wrap(command: String): String =
        "{ $command; } 2>&1; rc=\$?; echo $RC_MARKER\$rc"

    internal fun classifyOutput(output: String): Boolean {
        val marker = output.lastIndexOf(RC_MARKER)
        if (marker < 0) return false
        val rc = output.substring(marker + RC_MARKER.length).trim().toIntOrNull()
        return rc == 0
    }

    /** An APK path that can be placed unquoted on the vendor shell command line. */
    internal fun shellSafeApkPath(path: String): Boolean = SAFE_PATH.matches(path)

    internal fun hotspotCommand(packageCodePath: String, action: String): String =
        "CLASSPATH=$packageCodePath app_process /system/bin com.shilapi.xcertplay.system.PrivilegedHotspotTool $action"

    fun execute(command: String, timeoutMillis: Long = ROOT_COMMAND_TIMEOUT_MILLIS): Boolean {
        if (command.contains('\n') || command.contains('\r')) return false
        val wrapped = wrap(command)
        if (wrapped.toByteArray(Charsets.UTF_8).size > MAX_COMMAND_BYTES) {
            DiagLog.w(TAG, "Root command exceeds $MAX_COMMAND_BYTES bytes")
            return false
        }
        if (!inFlight.compareAndSet(false, true)) {
            DiagLog.w(TAG, "Root command still in flight")
            return false
        }
        val done = AtomicReference<Boolean?>(null)
        val worker = Thread({
            try {
                done.set(executeBlocking(wrapped))
            } finally {
                inFlight.set(false)
            }
        }, "root-shell")
        worker.isDaemon = true
        worker.start()
        worker.join(timeoutMillis.coerceAtLeast(0L))
        return done.get() ?: run {
            DiagLog.w(TAG, "Root command exceeded ${timeoutMillis}ms")
            false
        }
    }

    private fun executeBlocking(wrapped: String): Boolean {
        val binder = findExtraUtilsService() ?: return false
        return try {
            classifyOutput(transact(binder, wrapped))
        } catch (error: Throwable) {
            DiagLog.w(TAG, "Root transaction failed: ${error.message}")
            false
        }
    }

    private fun findExtraUtilsService(): IBinder? = try {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getService = serviceManager.getMethod("getService", String::class.java)
        getService.invoke(null, SERVICE_NAME) as? IBinder
    } catch (_: Throwable) {
        null
    }

    private fun transact(binder: IBinder, command: String): String {
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            request.writeInterfaceToken(DESCRIPTOR)
            request.writeInt(EVENT_SHELL)
            request.writeInt(ACTION_EXEC)
            request.writeByteArray(command.toByteArray(Charsets.UTF_8))
            if (!binder.transact(TRANSACTION_GET_MESSAGE, request, reply, 0)) {
                ""
            } else {
                reply.readException()
                val bytes = reply.createByteArray() ?: ByteArray(0)
                String(bytes, Charsets.UTF_8)
            }
        } finally {
            reply.recycle()
            request.recycle()
        }
    }
}
