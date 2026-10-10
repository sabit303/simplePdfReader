package com.simplepdf.reader.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.TextView

object Palette {
    val background = Color.rgb(247, 248, 245)
    val ink = Color.rgb(28, 38, 32)
    val muted = Color.rgb(105, 116, 108)
    val green = Color.rgb(40, 99, 78)
    val softGreen = Color.rgb(228, 239, 231)
    val line = Color.rgb(221, 228, 222)
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun rounded(color: Int, radius: Float = 16f): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

fun Context.label(text: CharSequence, size: Float = 14f, bold: Boolean = false) =
    TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(Palette.ink)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL
    }

fun Context.action(text: CharSequence, onClick: () -> Unit) = Button(this).apply {
    this.text = text
    isAllCaps = false
    textSize = 14f
    minWidth = 0
    minimumWidth = 0
    minHeight = dp(48)
    setTextColor(Palette.green)
    background = rounded(Palette.softGreen, dp(14).toFloat())
    setPadding(dp(16), 0, dp(16), 0)
    setOnClickListener { onClick() }
}
