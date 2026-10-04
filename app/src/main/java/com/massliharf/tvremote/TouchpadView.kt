package com.massliharf.tvremote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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

    var hintText = "Kaydır · Dokun · 2 parmakla kaydır"
        set(value) { field = value; invalidate() }

    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var travelled = 0f
    private var multi = false
    private var touching = false
    private var fingerX = 0f
    private var fingerY = 0f
    private val sensitivity = 1.6f

    private val lip = dp(6f)
    private val border = dp(2f)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = border }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.bold(context)
        textAlign = Paint.Align.CENTER
        textSize = dp(14f)
    }
    private val icon = context.getDrawable(R.drawable.ic_touch)!!.mutate()
    private val rect = RectF()

    private fun c(id: Int) = context.getColor(id)

    override fun onDraw(canvas: Canvas) {
        val radius = dp(32f)
        fill.color = c(R.color.key_border)
        rect.set(0f, lip, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, fill)
        rect.set(0f, 0f, width.toFloat(), height - lip)
        fill.color = c(R.color.key_pressed)
        canvas.drawRoundRect(rect, radius, radius, fill)
        stroke.color = c(R.color.key_border)
        rect.inset(border / 2, border / 2)
        canvas.drawRoundRect(rect, radius, radius, stroke)

        if (touching) {
            fill.color = c(R.color.blue)
            fill.alpha = 60
            canvas.drawCircle(fingerX, fingerY, dp(36f), fill)
            fill.alpha = 255
        } else {
            val s = dp(44f).toInt()
            val cx = width / 2f
            val cy = (height - lip) / 2f - dp(14f)
            icon.setTint(c(R.color.muted))
            icon.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
            icon.draw(canvas)
            hint.color = c(R.color.muted)
            canvas.drawText(hintText, cx, cy + s / 2 + dp(22f), hint)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y
                downTime = e.eventTime
                travelled = 0f
                multi = false
                touching = true
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
                touching = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> {
                touching = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        fingerX = e.getX(0)
        fingerY = e.getY(0)
        invalidate()
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
