package com.massliharf.tvremote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * A chunky "3D" button: a coloured face sitting on a darker lip. Pressing it pushes the
 * face down onto the lip, like the buttons in Duolingo.
 */
class KeyButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Kind { NEUTRAL, GREEN, BLUE, RED, YELLOW, PURPLE, ORANGE }

    var icon: Drawable? = null
        set(value) { field = value?.mutate(); invalidate() }
    var text: String? = null
        set(value) { field = value; if (value != null) contentDescription = value; requestLayout(); invalidate() }
    var kind = Kind.NEUTRAL
        set(value) { field = value; invalidate() }

    /** Shows the light-blue "toggled on" look used for active modes. */
    var selectedLook = false
        set(value) { field = value; invalidate() }

    /** Overrides the icon/text colour (e.g. a coloured icon on a neutral key). */
    var contentColor: Int? = null
        set(value) { field = value; invalidate() }

    private var shape = 0 // 0 rounded, 1 circle, 2 pill
    private var vertical = false
    private var alignStart = false
    private var caps = true
    private var iconSize = dp(26f)
    private val lip = dp(4f)
    private val border = dp(2f)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = border
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.extraBold(context)
        textSize = dp(15f)
    }
    private val rect = RectF()

    init {
        isClickable = true
        isFocusable = true
        val a = context.obtainStyledAttributes(attrs, R.styleable.KeyButton)
        icon = a.getDrawable(R.styleable.KeyButton_kbIcon)
        text = a.getString(R.styleable.KeyButton_kbText)
        kind = Kind.values()[a.getInt(R.styleable.KeyButton_kbKind, 0)]
        shape = a.getInt(R.styleable.KeyButton_kbShape, 0)
        vertical = a.getBoolean(R.styleable.KeyButton_kbVertical, false)
        alignStart = a.getBoolean(R.styleable.KeyButton_kbAlignStart, false)
        caps = a.getBoolean(R.styleable.KeyButton_kbCaps, !alignStart)
        iconSize = a.getDimension(R.styleable.KeyButton_kbIconSize, iconSize)
        textPaint.textSize = a.getDimension(R.styleable.KeyButton_kbTextSize, textPaint.textSize)
        a.recycle()
        if (caps) textPaint.letterSpacing = 0.04f
    }

    fun setAlignStart() {
        alignStart = true
        caps = false
        textPaint.letterSpacing = 0f
        requestLayout()
        invalidate()
    }

    private fun color(id: Int) = context.getColor(id)

    private fun palette(): IntArray = when {
        // face, lip/border, content, hasBorder(1/0)
        selectedLook -> intArrayOf(color(R.color.sel_face), color(R.color.sel_border), color(R.color.sel_content), 1)
        kind == Kind.NEUTRAL -> intArrayOf(color(R.color.key_face), color(R.color.key_border), color(R.color.key_content), 1)
        kind == Kind.GREEN -> intArrayOf(color(R.color.green), color(R.color.green_lip), WHITE, 0)
        kind == Kind.BLUE -> intArrayOf(color(R.color.blue), color(R.color.blue_lip), WHITE, 0)
        kind == Kind.RED -> intArrayOf(color(R.color.red), color(R.color.red_lip), WHITE, 0)
        kind == Kind.YELLOW -> intArrayOf(color(R.color.yellow), color(R.color.yellow_lip), WHITE, 0)
        kind == Kind.PURPLE -> intArrayOf(color(R.color.purple), color(R.color.purple_lip), WHITE, 0)
        else -> intArrayOf(color(R.color.orange), color(R.color.orange_lip), WHITE, 0)
    }

    private fun label(): String? = text?.let { if (caps) it.uppercase() else it }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val label = label()
        val textW = label?.let { textPaint.measureText(it) } ?: 0f
        val hasIcon = icon != null
        val gap = if (hasIcon && label != null) dp(8f) else 0f
        val contentW = if (vertical) maxOf(textW, if (hasIcon) iconSize else 0f)
                       else textW + gap + (if (hasIcon) iconSize else 0f)
        val wantW = (contentW + dp(32f)).toInt().coerceAtLeast(dp(56f).toInt())
        val wantH = dp(56f).toInt()
        setMeasuredDimension(resolveSize(wantW, widthMeasureSpec), resolveSize(wantH, heightMeasureSpec))
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.45f
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val (face, lipColor, content0, hasBorder) = palette().let { arrayOf(it[0], it[1], it[2], it[3]) }
        val content = if (!selectedLook && contentColor != null) contentColor!! else content0
        val pressedOffset = if (isPressed && isEnabled) lip else 0f

        var left = 0f
        var top = 0f
        var right = width.toFloat()
        var bottom = height.toFloat()
        if (shape == 1) { // circle: square area centred in the view
            val s = min(right, bottom)
            left = (right - s) / 2; top = (bottom - s) / 2
            right = left + s; bottom = top + s
        }
        val faceH = bottom - top - lip
        val radius = when (shape) {
            0 -> min(dp(16f), faceH / 2)
            else -> faceH / 2
        }

        // Lip
        fill.color = lipColor
        rect.set(left, top + lip, right, bottom)
        canvas.drawRoundRect(rect, radius, radius, fill)

        // Face
        rect.set(left, top + pressedOffset, right, bottom - lip + pressedOffset)
        fill.color = face
        canvas.drawRoundRect(rect, radius, radius, fill)
        if (hasBorder == 1) {
            stroke.color = lipColor
            val inset = border / 2
            rect.inset(inset, inset)
            canvas.drawRoundRect(rect, radius - inset, radius - inset, stroke)
            rect.inset(-inset, -inset)
        }

        // Content
        val label = label()
        textPaint.color = content
        icon?.setTint(content)
        val cx = rect.centerX()
        val cy = rect.centerY()
        val fm = textPaint.fontMetrics
        val textH = fm.descent - fm.ascent
        val ic = icon
        val s = iconSize.toInt()

        if (alignStart) {
            var x = rect.left + dp(18f)
            if (ic != null) {
                ic.setBounds(x.toInt(), (cy - s / 2).toInt(), x.toInt() + s, (cy + s / 2).toInt())
                ic.draw(canvas)
                x += s + dp(14f)
            }
            if (label != null) {
                textPaint.color = if (contentColor != null && kind == Kind.NEUTRAL && !selectedLook)
                    color(R.color.text) else content
                val avail = rect.right - dp(18f) - x
                val shown = TextUtils.ellipsize(label, TextPaint(textPaint), avail, TextUtils.TruncateAt.END)
                canvas.drawText(shown.toString(), x, cy - (fm.ascent + fm.descent) / 2, textPaint)
            }
        } else if (vertical && ic != null && label != null) {
            val total = s + dp(4f) + textH
            val iconTop = cy - total / 2
            ic.setBounds((cx - s / 2).toInt(), iconTop.toInt(), (cx + s / 2).toInt(), (iconTop + s).toInt())
            ic.draw(canvas)
            val tw = textPaint.measureText(label)
            canvas.drawText(label, cx - tw / 2, iconTop + s + dp(4f) - fm.ascent, textPaint)
        } else {
            val tw = label?.let { textPaint.measureText(it) } ?: 0f
            val gap = if (ic != null && label != null) dp(8f) else 0f
            val total = (if (ic != null) s.toFloat() else 0f) + gap + tw
            var x = cx - total / 2
            if (ic != null) {
                ic.setBounds(x.toInt(), (cy - s / 2).toInt(), x.toInt() + s, (cy + s / 2).toInt())
                ic.draw(canvas)
                x += s + gap
            }
            if (label != null) canvas.drawText(label, x, cy - (fm.ascent + fm.descent) / 2, textPaint)
        }
    }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
