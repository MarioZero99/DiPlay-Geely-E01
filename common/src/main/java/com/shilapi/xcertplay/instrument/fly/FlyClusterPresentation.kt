package com.shilapi.xcertplay.instrument.fly

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * One Surface on the cluster's own display.
 *
 * It is not focusable and not touchable: the driver's fingers belong to the centre screen, and a
 * focusable window here would take them. The surface is handed to the controller as soon as the
 * holder has one, and taken back when the window goes away.
 */
internal class FlyClusterPresentation(
    context: Context,
    display: Display,
    private val onSurface: (Surface?) -> Unit,
) : Presentation(context, display) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        )
        val root = FrameLayout(context)
        val surfaceView = SurfaceView(context)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = onSurface(holder.surface)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) = onSurface(null)
        })
        root.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }
}
