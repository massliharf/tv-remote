package com.massliharf.tvremote

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/** The big round navigation pad: four arrow zones around a green OK button. */
class DpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onKey: ((String) -> Unit)? = null

    private val zones = arrayOf("RIGHT", "DOWN", "LEFT", "UP") // clockwise from 3 o'clock
    private val arrows = arrayOf(R.drawable.ic_right, R.drawable.ic_down, R.drawable.ic_left, R.drawable.ic_up)
        .map { context.getDrawable(it)!!.mutate() }
    private var pressed: String? = null
    private val repeater = Repeater()

    private val lip = dp(6f)
    private val okLip = dp(5f)
    private val border = dp(2f)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = border }
    private val okText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.extraBold(context)
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val oval = RectF()

    private var cx = 0f
    private var cy = 0f
    private var r = 0f
    private var rOk = 0f

    init {
        isClickable = true
        contentDescription = "Yön tuşları"
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val size = min(w, h).toFloat()
        cx = w / 2f
        cy = h / 2f - lip / 2
        r = size / 2 - lip / 2 - border
        rOk = r * 0.42f
        okText.textSize = rOk * 0.55f
    }

    private fun c(id: Int) = context.getColor(id)

    override fun onDraw(canvas: Canvas) {
        // Outer disc: lip, face, pressed wedge, border
        fill.color = c(R.color.key_border)
        canvas.drawCircle(cx, cy + lip, r, fill)
        fill.color = c(R.color.key_face)
        canvas.drawCircle(cx, cy, r, fill)

        val p = pressed
        if (p != null && p != "ENTER") {
            val i = zones.indexOf(p)
            fill.color = c(R.color.key_pressed)
            oval.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(oval, i * 90f - 45f, 90f, true, fill)
        }
        stroke.color = c(R.color.key_border)
        canvas.drawCircle(cx, cy, r - border / 2, stroke)

        // Arrows
        val ringMid = (r + rOk) / 2 + dp(4f)
        val s = dp(34f).toInt()
        for (i in 0 until 4) {
            val angle = Math.toRadians(i * 90.0)
            val ax = cx + (ringMid * Math.cos(angle)).toFloat()
            val ay = cy + (ringMid * Math.sin(angle)).toFloat() + if (p == zones[i]) dp(2f) else 0f
            val d = arrows[i]
            d.setTint(c(if (p == zones[i]) R.color.blue else R.color.key_content))
            d.setBounds((ax - s / 2).toInt(), (ay - s / 2).toInt(), (ax + s / 2).toInt(), (ay + s / 2).toInt())
            d.draw(canvas)
        }

        // OK button
        val okDown = if (p == "ENTER") okLip else 0f
        fill.color = c(R.color.green_lip)
        canvas.drawCircle(cx, cy + okLip / 2, rOk, fill)
        fill.color = c(R.color.green)
        canvas.drawCircle(cx, cy - okLip / 2 + okDown, rOk, fill)
        val fm = okText.fontMetrics
        canvas.drawText("OK", cx, cy - okLip / 2 + okDown - (fm.ascent + fm.descent) / 2, okText)
    }

    private fun zoneAt(x: Float, y: Float): String? {
        val dx = x - cx
        val dy = y - cy
        val dist = hypot(dx, dy)
        if (dist > r + lip * 2) return null
        if (dist < rOk + dp(4f)) return "ENTER"
        var deg = Math.toDegrees(atan2(dy, dx).toDouble())
        if (deg < 0) deg += 360.0
        return zones[(((deg + 45) % 360) / 90).toInt()]
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val z = zoneAt(e.x, e.y) ?: return false
                pressed = z
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                if (z == "ENTER") onKey?.invoke(z) else repeater.start { onKey?.invoke(z) }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                repeater.stop()
                pressed = null
                invalidate()
            }
        }
        return true
    }
}
