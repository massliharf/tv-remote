package com.massliharf.tvremote

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/** Nunito, the rounded typeface that gives the app its friendly, Duolingo-like feel. */
object Fonts {
    private var bold: Typeface? = null
    private var extraBold: Typeface? = null

    fun bold(c: Context): Typeface =
        bold ?: Typeface.createFromAsset(c.assets, "fonts/nunito_bold.ttf").also { bold = it }

    fun extraBold(c: Context): Typeface =
        extraBold ?: Typeface.createFromAsset(c.assets, "fonts/nunito_extrabold.ttf").also { extraBold = it }

    /** Applies Nunito to every TextView in a view tree (bold text gets the ExtraBold cut). */
    fun apply(v: View) {
        when (v) {
            is ViewGroup -> for (i in 0 until v.childCount) apply(v.getChildAt(i))
            is TextView -> v.typeface =
                if (v.typeface?.isBold == true) extraBold(v.context) else bold(v.context)
        }
    }
}

fun View.dp(value: Float): Float = value * resources.displayMetrics.density

/** Fires an action immediately, then keeps repeating it while a key is held. */
class Repeater {
    private val handler = Handler(Looper.getMainLooper())
    private var task: Runnable? = null

    fun start(action: () -> Unit) {
        stop()
        action()
        val r = object : Runnable {
            override fun run() {
                action()
                handler.postDelayed(this, 130)
            }
        }
        task = r
        handler.postDelayed(r, 450)
    }

    fun stop() {
        task?.let { handler.removeCallbacks(it) }
        task = null
    }
}
