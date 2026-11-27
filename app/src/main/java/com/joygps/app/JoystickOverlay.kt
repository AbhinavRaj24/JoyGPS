package com.joygps.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * A draggable joystick window that floats over other apps (maps, games, etc.),
 * so you can keep walking while the app you're testing is in front.
 */
@SuppressLint("ClickableViewAccessibility", "SetTextI18n")
class JoystickOverlay(
    private val context: Context,
    private val onClose: () -> Unit,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val dp = context.resources.displayMetrics.density
    private var attached = false

    private val speedLabel = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 13f
        text = "☰  0.0 km/h"
        setPadding(px(12), px(6), px(8), px(6))
    }

    private val closeButton = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 15f
        text = "✕"
        setPadding(px(10), px(4), px(12), px(4))
        setOnClickListener { onClose() }
    }

    private val joystick = JoystickView(context).apply {
        onMove = { x, y -> MockState.setInput(x, y) }
    }

    private val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = 16f * dp
            setColor(Color.argb(190, 15, 23, 32))
        }
        addView(speedLabel)
        addView(closeButton)
    }

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = px(6) })
        addView(joystick, LinearLayout.LayoutParams(px(150), px(150)))
    }

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = px(16)
        y = (context.resources.displayMetrics.heightPixels * 0.55f).toInt()
    }

    init {
        // Drag the window around by its header.
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        speedLabel.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = e.rawX
                    touchY = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - touchX).toInt()
                    params.y = startY + (e.rawY - touchY).toInt()
                    if (attached) wm.updateViewLayout(root, params)
                }
            }
            true
        }
    }

    fun show() {
        if (attached) return
        wm.addView(root, params)
        attached = true
        update()
    }

    fun hide() {
        if (!attached) return
        joystick.reset()
        wm.removeView(root)
        attached = false
    }

    fun update() {
        if (!attached) return
        speedLabel.text = String.format(Locale.US, "☰  %.1f km/h", MockState.speedKmh)
    }

    private fun px(v: Int): Int = (v * dp).toInt()
}
