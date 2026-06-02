package com.shrine.launcher.ui.settings

import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts

class WallpaperAppearanceActivity : BaseSettingsActivity() {

    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@registerForActivityResult
        val strings = uris.map { uri ->
            contentResolver.takePersistableUriPermission(uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            uri.toString()
        }
        if (strings.size == 1) {
            saveAndRefresh { it.copy(wallpaperUri = strings[0], wallpaperUris = emptyList(), wallpaperSlideshow = false) }
        } else {
            saveAndRefresh { it.copy(wallpaperUri = null, wallpaperUris = strings, wallpaperSlideshow = true) }
        }
        recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupBase("Wallpaper", intent.getStringExtra("wallpaper_uri"))
        val prefs = prefRepo.loadPrefs()

        addToggle("Slideshow mode", "Cycle through multiple wallpapers",
            isChecked = prefs.wallpaperSlideshow) { checked ->
            saveAndRefresh { it.copy(wallpaperSlideshow = checked) }
        }

        addButton("Select wallpaper(s)", "Pick one image or multiple for slideshow") {
            imagePicker.launch(arrayOf("image/*"))
        }

        if (prefs.wallpaperUri != null || prefs.wallpaperUris.isNotEmpty()) {
            addButton("Remove wallpaper", textColor = 0xFFCF6679.toInt()) {
                saveAndRefresh { it.copy(wallpaperUri = null, wallpaperUris = emptyList(), wallpaperSlideshow = false) }
                recreate()
            }
        }

        val intervals = listOf(30, 60, 120, 300, 600, 900, 1800)
        val labels    = listOf("30s", "1min", "2min", "5min", "10min", "15min", "30min")
        val currIdx   = intervals.indexOf(prefs.wallpaperIntervalSeconds).coerceAtLeast(0)
        addButton("Slide interval: ${labels.getOrElse(currIdx) { "5min" }}") {
            android.app.AlertDialog.Builder(this)
                .setTitle("Slide interval")
                .setSingleChoiceItems(labels.toTypedArray(), currIdx) { d, which ->
                    saveAndRefresh { it.copy(wallpaperIntervalSeconds = intervals[which]) }
                    d.dismiss()
                    recreate()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
}
