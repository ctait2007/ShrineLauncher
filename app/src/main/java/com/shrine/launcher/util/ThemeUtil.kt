package com.shrine.launcher.util

import android.app.Activity
import android.graphics.Color
import android.view.View
import com.shrine.launcher.data.model.LauncherTheme

object ThemeUtil {

    fun applyToActivity(activity: Activity, rootView: View, theme: LauncherTheme) {
        try {
            val bgColor = Color.parseColor(theme.backgroundColorHex)
            rootView.setBackgroundColor(bgColor)
            activity.window.statusBarColor   = bgColor
            activity.window.navigationBarColor = bgColor
        } catch (e: IllegalArgumentException) {
            // keep existing colour if parse fails
        }
    }

    fun parseColorSafe(hex: String, fallback: Int = Color.BLACK): Int {
        return try { Color.parseColor(hex) } catch (e: Exception) { fallback }
    }
}
