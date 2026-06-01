package com.shrine.launcher.util

import android.graphics.drawable.GradientDrawable
import android.view.View

object CardStyleUtil {
    fun applyCornerRadius(view: View, cardSizeDp: Int, cornerRadiusPercent: Int) {
        val density    = view.resources.displayMetrics.density
        val cardSizePx = cardSizeDp * density
        val radius     = (cardSizePx * cornerRadiusPercent / 100f / 2f)
        val bg = GradientDrawable().apply {
            shape         = GradientDrawable.RECTANGLE
            setColor(0xFF1E1E1E.toInt())
            cornerRadius  = radius
        }
        view.background = bg
    }
}
