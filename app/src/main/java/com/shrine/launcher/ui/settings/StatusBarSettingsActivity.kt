package com.shrine.launcher.ui.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings

class StatusBarSettingsActivity : BaseSettingsActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupBase("Status Bar", intent.getStringExtra("wallpaper_uri"))
        val prefs = prefRepo.loadPrefs()

        addToggle("Show clock", isChecked = prefs.clockEnabled) { checked ->
            saveAndRefresh { it.copy(clockEnabled = checked) }
        }

        addToggle("Show date", isChecked = prefs.dateEnabled) { checked ->
            saveAndRefresh { it.copy(dateEnabled = checked) }
        }

        addToggle("24-hour clock", isChecked = prefs.clockFormat24h) { checked ->
            saveAndRefresh { it.copy(clockFormat24h = checked) }
        }

        addButton("Wi-Fi settings") {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }

        addSlider("Status bar icon size",
            value = prefs.statusBarIconSizePercent,
            min = 50, max = 150, step = 10,
            displayFn = { "$it%" }) { v ->
            saveAndRefresh { it.copy(statusBarIconSizePercent = v) }
        }
    }
}
