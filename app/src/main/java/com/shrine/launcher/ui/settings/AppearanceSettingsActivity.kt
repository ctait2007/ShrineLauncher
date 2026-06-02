package com.shrine.launcher.ui.settings

import android.content.Intent
import android.os.Bundle

class AppearanceSettingsActivity : BaseSettingsActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupBase("Appearance", intent.getStringExtra("wallpaper_uri"))
        val wu = intent.getStringExtra("wallpaper_uri")

        addButton("Categories / Channels") {
            startActivity(Intent(this, CategoryAppearanceActivity::class.java)
                .apply { wu?.let { putExtra("wallpaper_uri", it) } })
        }
        addButton("Cards") {
            startActivity(Intent(this, CardAppearanceActivity::class.java)
                .apply { wu?.let { putExtra("wallpaper_uri", it) } })
        }
        addButton("Wallpaper") {
            startActivity(Intent(this, WallpaperAppearanceActivity::class.java)
                .apply { wu?.let { putExtra("wallpaper_uri", it) } })
        }
        addButton("Status bar") {
            startActivity(Intent(this, StatusBarSettingsActivity::class.java)
                .apply { wu?.let { putExtra("wallpaper_uri", it) } })
        }
        addButton("Idle mode") {
            startActivity(Intent(this, IdleModeSettingsActivity::class.java)
                .apply { wu?.let { putExtra("wallpaper_uri", it) } })
        }
    }
}
