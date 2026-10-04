package com.massliharf.tvremote

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/** A tall pill with + and − halves, like the volume / channel rockers on a real remote. */
class RockerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onKey: ((String) -> Unit)? = null

    private val label: String
    private val topKey: String
    private val bottomKey: String
    private val plus = context.getDrawable(R.drawable.ic_add)!!.mutate()
    private val minus = context.getDrawable(R.drawable.ic_remove)!!.mutate()
    private var pressedTop: Boolean? = null
    private val repeater = Repeater()

    private val lip = dp(5f)
    private val border = dp(2f)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = border }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.extraBold(context)
        textAlign = Paint.Align.CENTER
        textSize = dp(13f)
        letterSpacing = 0.08f
    }
    private val rect = RectF()
    private val clip = Path()

    init {
        isClickable = true
        val a = context.obtainStyledAttributes(attrs, R.styleable.RockerView)
        label = a.getString(R.styleable.RockerView_rkLabel) ?: ""
        topKey = a.getString(R.styleable.RockerView_rkTopKey) ?: ""
        bottomKey = a.getString(R.styleable.RockerView_rkBottomKey) ?: ""
        a.recycle()
        contentDescription = label
    }

    private fun c(id: Int) = context.getColor(id)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val faceBottom = height - lip
        val radius = w / 2

        fill.color = c(R.color.key_border)
        rect.set(0f, lip, w, height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, fill)

        rect.set(0f, 0f, w, faceBottom)
        fill.color = c(R.color.key_face)
        canvas.drawRoundRect(rect, radius, radius, fill)

        val pt = pressedTop
        if (pt != null) {
            clip.reset()
            clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            fill.color = c(R.color.key_pressed)
            if (pt) canvas.drawRect(0f, 0f, w, faceBottom / 2, fill)
            else canvas.drawRect(0f, faceBottom / 2, w, faceBottom, fill)
            canvas.restore()
        }
        stroke.color = c(R.color.key_border)
        rect.inset(border / 2, border / 2)
        canvas.drawRoundRect(rect, radius - border / 2, radius - border / 2, stroke)

        val s = dp(30f).toInt()
        val cx = w / 2
        val topY = faceBottom * 0.2f + if (pt == true) dp(2f) else 0f
        val botY = faceBottom * 0.8f + if (pt == false) dp(2f) else 0f
        plus.setTint(c(if (pt == true) R.color.blue else R.color.key_content))
        minus.setTint(c(if (pt == false) R.color.blue else R.color.key_content))
        plus.setBounds((cx - s / 2).toInt(), (topY - s / 2).toInt(), (cx + s / 2).toInt(), (topY + s / 2).toInt())
        minus.setBounds((cx - s / 2).toInt(), (botY - s / 2).toInt(), (cx + s / 2).toInt(), (botY + s / 2).toInt())
        plus.draw(canvas)
        minus.draw(canvas)

        labelPaint.color = c(R.color.muted)
        val fm = labelPaint.fontMetrics
        canvas.drawText(label, cx, faceBottom / 2 - (fm.ascent + fm.descent) / 2, labelPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val top = e.y < (height - lip) / 2
                pressedTop = top
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                val key = if (top) topKey else bottomKey
                repeater.start { onKey?.invoke(key) }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                repeater.stop()
                pressedTop = null
                invalidate()
            }
        }
        return true
    }
}
