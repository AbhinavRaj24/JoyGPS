package com.joygps.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * A round on-screen joystick.
 *
 * Reports a vector through [onMove]: x = right/east, y = up/north, with length 0..1
 * equal to how far the knob is stretched from the centre. Releasing the knob
 * springs it back and reports (0, 0).
 */
class JoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var onMove: ((x: Float, y: Float) -> Unit)? = null

    private val dp = resources.displayMetrics.density

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(110, 20, 30, 45)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * dp
        color = Color.argb(210, 255, 255, 255)
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * dp
        color = Color.argb(80, 255, 255, 255)
    }
    private val stickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * dp
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(150, 255, 255, 255)
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(0x2E, 0x8B, 0xF0)
    }
    private val knobRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * dp
        color = Color.WHITE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        textAlign = Paint.Align.CENTER
        textSize = 11f * dp
        typeface = Typeface.DEFAULT_BOLD
    }

    private var cx = 0f
    private var cy = 0f
    private var baseR = 0f
    private var knobR = 0f
    private var travel = 0f

    // Knob offset from centre, in px.
    private var kx = 0f
    private var ky = 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (160 * dp).toInt()
        val size = min(resolveSize(desired, widthMeasureSpec), resolveSize(desired, heightMeasureSpec))
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f
        cy = h / 2f
        val half = min(w, h) / 2f
        baseR = half - ringPaint.strokeWidth
        knobR = half * 0.32f
        travel = baseR - knobR
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawCircle(cx, cy, baseR, basePaint)
        canvas.drawCircle(cx, cy, baseR, ringPaint)
        canvas.drawCircle(cx, cy, travel * 0.5f, guidePaint)
        canvas.drawLine(cx - travel, cy, cx + travel, cy, guidePaint)
        canvas.drawLine(cx, cy - travel, cx, cy + travel, guidePaint)
        canvas.drawText("N", cx, cy - baseR + 4f * dp + textPaint.textSize, textPaint)

        if (kx != 0f || ky != 0f) canvas.drawLine(cx, cy, cx + kx, cy + ky, stickPaint)
        canvas.drawCircle(cx + kx, cy + ky, knobR, knobPaint)
        canvas.drawCircle(cx + kx, cy + ky, knobR, knobRingPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                var dx = event.x - cx
                var dy = event.y - cy
                val dist = hypot(dx, dy)
                if (dist > travel && dist > 0f) {
                    val s = travel / dist
                    dx *= s
                    dy *= s
                }
                kx = dx
                ky = dy
                report()
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                reset()
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun setEnabled(enabled: Boolean) {
        val changed = enabled != isEnabled
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.4f
        if (changed && !enabled) reset()
    }

    /** Springs the knob back to the centre and reports zero input. */
    fun reset() {
        kx = 0f
        ky = 0f
        report()
        invalidate()
    }

    private fun report() {
        if (travel <= 0f) {
            onMove?.invoke(0f, 0f)
            return
        }
        val nx = kx / travel
        val ny = -ky / travel // screen y grows downwards; north is up
        val m = hypot(nx, ny).coerceAtMost(1f)
        if (m < DEAD_ZONE) {
            onMove?.invoke(0f, 0f)
            return
        }
        // Re-map so speed ramps smoothly from 0 just outside the dead zone to 1 at the rim.
        val scaled = (m - DEAD_ZONE) / (1f - DEAD_ZONE)
        onMove?.invoke(nx / m * scaled, ny / m * scaled)
    }

    private companion object {
        const val DEAD_ZONE = 0.08f
    }
}
