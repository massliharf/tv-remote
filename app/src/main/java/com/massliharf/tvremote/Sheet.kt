package com.massliharf.tvremote

import android.app.Activity
import android.app.Dialog
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** A rounded bottom sheet that slides up from the bottom of the screen. */
class Sheet(activity: Activity, title: String, content: View) : Dialog(activity, R.style.SheetDialog) {

    init {
        val ctx = activity
        val density = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * density).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.sheet_bg)
            setPadding(px(18), px(10), px(18), px(20))
        }
        root.addView(View(ctx).apply { setBackgroundResource(R.drawable.handle_bg) },
            LinearLayout.LayoutParams(px(44), px(5)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = px(14)
            })
        root.addView(TextView(ctx).apply {
            text = title
            textSize = 22f
            setTextColor(ctx.getColor(R.color.text))
            typeface = Fonts.extraBold(ctx)
            setPadding(px(6), 0, px(6), px(8))
        })
        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(content)
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        Fonts.apply(content)
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window?.setGravity(Gravity.BOTTOM)
    }
}
