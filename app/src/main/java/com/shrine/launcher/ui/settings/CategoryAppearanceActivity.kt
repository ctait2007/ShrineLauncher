package com.shrine.launcher.ui.settings

import android.os.Bundle

class CategoryAppearanceActivity : BaseSettingsActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupBase("Category / Channel Appearance", intent.getStringExtra("wallpaper_uri"))
        val prefs = prefRepo.loadPrefs()

        addToggle("Show category title",
            isChecked = prefs.showCategoryTitle) { checked ->
            saveAndRefresh { it.copy(showCategoryTitle = checked) }
        }

        addToggle("Show progress bar", "Show progress on Continue Watching cards",
            isChecked = prefs.progressBarEnabled) { checked ->
            saveAndRefresh { it.copy(progressBarEnabled = checked) }
        }

        addSlider("Bottom margin", value = prefs.rowsBottomMarginPercent,
            min = 0, max = 100, step = 5,
            displayFn = { "$it%" }) { v ->
            saveAndRefresh { it.copy(rowsBottomMarginPercent = v) }
        }

        // rowStartPaddingDp stores 0–100 percentage; rendered as (value/100)*120dp
        addSlider("Start margin", value = prefs.rowStartPaddingDp.coerceIn(0, 100),
            min = 0, max = 100, step = 5,
            displayFn = { "$it%" }) { v ->
            saveAndRefresh { it.copy(rowStartPaddingDp = v) }
        }

        addSlider("Row spacing", value = prefs.rowSpacingPercent,
            min = 0, max = 100, step = 5,
            displayFn = { "$it%" }) { v ->
            saveAndRefresh { it.copy(rowSpacingPercent = v) }
        }
    }
}
