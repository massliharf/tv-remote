package com.massliharf.tvremote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

/** A trackpad surface: one finger moves the TV pointer, a tap clicks, two fingers scroll. */
class TouchpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onMove(dx: Int, dy: Int)
        fun onTap()
        fun onScroll(dx: Int, dy: Int)
    }

    var listener: Listener? = null

    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var travelled = 0f
    private var multi = false
    private val sensitivity = 1.6f

    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6B7280.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 14f * resources.displayMetrics.scaledDensity
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawText("Kaydır: imleç · Dokun: tıkla · 2 parmak: kaydır",
            width / 2f, height / 2f, hint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y
                downTime = e.eventTime
                travelled = 0f
                multi = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multi = true
                lastX = e.getX(0); lastY = e.getY(0)
            }
            MotionEvent.ACTION_MOVE -> {
                val x = e.getX(0)
                val y = e.getY(0)
                val dx = ((x - lastX) * sensitivity).roundToInt()
                val dy = ((y - lastY) * sensitivity).roundToInt()
                if (dx != 0 || dy != 0) {
                    travelled += abs(x - lastX) + abs(y - lastY)
                    if (multi) listener?.onScroll(dx, dy) else listener?.onMove(dx, dy)
                    lastX = x; lastY = y
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!multi && travelled < 20f && e.eventTime - downTime < 250) {
                    listener?.onTap()
                    performClick()
                }
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
