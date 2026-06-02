package com.shrine.launcher.ui.settings

import android.os.Bundle
import com.shrine.launcher.data.model.CardDisplayMode

class CardAppearanceActivity : BaseSettingsActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupBase("Card Appearance", intent.getStringExtra("wallpaper_uri"))
        val prefs = prefRepo.loadPrefs()

        addToggle("Show app title", isChecked = prefs.showAppTitle) { checked ->
            saveAndRefresh { it.copy(showAppTitle = checked) }
        }

        addToggle("Banner mode", "Show wide banner art instead of square icons",
            isChecked = prefs.globalCardDisplayMode == CardDisplayMode.BANNER) { checked ->
            saveAndRefresh { it.copy(globalCardDisplayMode = if (checked) CardDisplayMode.BANNER else CardDisplayMode.ICON) }
        }

        addSlider("Corner roundness", value = prefs.cardCornerRadiusPercent,
            min = 0, max = 50, step = 5,
            displayFn = { "$it%" }) { v ->
            saveAndRefresh { it.copy(cardCornerRadiusPercent = v) }
        }

        addButton("Card size") {
            val sizes  = arrayOf("S — Small", "M — Medium", "L — Large", "XL — Extra Large")
            val labels = arrayOf("S", "M", "L", "XL")
            val curr   = labels.indexOf(prefRepo.loadPrefs().iconSizeLabel).coerceAtLeast(0)
            android.app.AlertDialog.Builder(this)
                .setTitle("Card size")
                .setSingleChoiceItems(sizes, curr) { d, which ->
                    saveAndRefresh { it.copy(iconSizeLabel = labels[which]) }
                    d.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        addSlider("Card spacing", value = prefs.itemSpacingPercent,
            min = 0, max = 40, step = 2,
            displayFn = { "$it%" }) { v ->
            saveAndRefresh { it.copy(itemSpacingPercent = v) }
        }
    }
}
