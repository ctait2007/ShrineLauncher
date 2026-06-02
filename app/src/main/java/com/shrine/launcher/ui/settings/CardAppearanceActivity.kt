package com.shrine.launcher.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.shrine.launcher.R
import com.shrine.launcher.data.model.CardDisplayMode

class CardAppearanceActivity : BaseSettingsActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupBase("Cards", intent.getStringExtra("wallpaper_uri"))
        val prefs = prefRepo.loadPrefs()

        addToggle("Show app title", isChecked = prefs.showAppTitle) { checked ->
            saveAndRefresh { it.copy(showAppTitle = checked) }
        }

        addToggle("Banner mode", "Show wide banner art instead of square icons",
            isChecked = prefs.globalCardDisplayMode == CardDisplayMode.BANNER) { checked ->
            saveAndRefresh { it.copy(globalCardDisplayMode = if (checked) CardDisplayMode.BANNER else CardDisplayMode.ICON) }
        }

        addSlider("Corner roundness", value = prefs.cardCornerRadiusPercent,
            min = 0, max = 100, step = 5,
            displayFn = { "$it%" }) { v ->
            saveAndRefresh { it.copy(cardCornerRadiusPercent = v) }
        }

        // Card size — inline button row
        addSectionHeader("CARD SIZE")
        addCardSizeButtons(prefs.iconSizeLabel)

        addSlider("Card spacing", value = prefs.itemSpacingPercent,
            min = 0, max = 100, step = 5,
            displayFn = { "$it%" }) { v ->
            saveAndRefresh { it.copy(itemSpacingPercent = v) }
        }
    }

    private fun addCardSizeButtons(currentSize: String) {
        val sizes = listOf("S" to "Small", "M" to "Medium", "L" to "Large", "XL" to "X-Large")
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, 4, 0, 0) }
        }

        val dp = resources.displayMetrics.density

        sizes.forEach { (label, name) ->
            val btn = TextView(this)
            btn.text = label
            btn.textSize = 13f
            btn.gravity = android.view.Gravity.CENTER
            btn.isFocusable = true
            btn.isClickable = true
            val lp = LinearLayout.LayoutParams(0, (44 * dp).toInt(), 1f)
            lp.setMargins((4 * dp).toInt(), 0, (4 * dp).toInt(), 0)
            btn.layoutParams = lp
            val isSelected = label == currentSize
            btn.setTextColor(if (isSelected) 0xFFFFFFFF.toInt() else 0xFFB0B0B0.toInt())
            btn.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(if (isSelected) 0xFFE53935.toInt() else 0x33FFFFFF)
                cornerRadius = 8 * dp
            }
            btn.setOnClickListener {
                saveAndRefresh { it.copy(iconSizeLabel = label) }
                // Refresh the whole screen so button states update
                recreate()
            }
            btn.setOnFocusChangeListener { v, hasFocus ->
                if (label != prefRepo.loadPrefs().iconSizeLabel) {
                    v.background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        setColor(0x33FFFFFF)
                        setStroke(if (hasFocus) (2 * dp).toInt() else 0,
                            if (hasFocus) 0xFFE53935.toInt() else 0)
                        cornerRadius = 8 * dp
                    }
                }
            }
            row.addView(btn)
        }

        val outerLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        outerLp.setMargins((4 * dp).toInt(), 0, (4 * dp).toInt(), (8 * dp).toInt())
        row.layoutParams = outerLp
        binding.llSubContent.addView(row)
    }
}
