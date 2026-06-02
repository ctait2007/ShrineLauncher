package com.shrine.launcher.ui.settings

import android.os.Bundle

class IdleModeSettingsActivity : BaseSettingsActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupBase("Idle Mode", intent.getStringExtra("wallpaper_uri"))
        val prefs = prefRepo.loadPrefs()

        addToggle("Enable idle mode",
            "Hide all UI after a period of inactivity",
            isChecked = prefs.idleModeEnabled) { checked ->
            saveAndRefresh { it.copy(idleModeEnabled = checked) }
        }

        // Timeout: 30s steps from 30s (0.5 min) to 300s (5 min)
        addSlider("Idle timeout",
            value = prefs.idleTimeoutSeconds,
            min = 30, max = 300, step = 30,
            displayFn = { s ->
                if (s < 60) "${s}s" else "${s / 60}min ${if (s % 60 > 0) "${s % 60}s" else ""}".trim()
            }) { v ->
            saveAndRefresh { it.copy(idleTimeoutSeconds = v) }
        }
    }
}
