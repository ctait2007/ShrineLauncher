package com.shrine.launcher.ui.settings

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.shrine.launcher.data.repository.PreferencesRepository

/**
 * Transparent single-purpose activity: shows the system image picker,
 * persists the selected URIs as the wallpaper, then finishes immediately.
 * Used by SettingsPanelDialog so the panel stays visible in the background.
 */
class WallpaperPickerActivity : AppCompatActivity() {

    private val prefRepo by lazy { PreferencesRepository.getInstance(this) }

    private val picker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val persisted = uris.mapNotNull { uri ->
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    uri.toString()
                } catch (e: Exception) { uri.toString() }
            }
            val p = prefRepo.loadPrefs()
            if (persisted.size == 1) {
                prefRepo.savePrefs(p.copy(
                    wallpaperUri = persisted[0], wallpaperUris = emptyList(), wallpaperSlideshow = false))
            } else {
                prefRepo.savePrefs(p.copy(
                    wallpaperUri = null, wallpaperUris = persisted, wallpaperSlideshow = true))
            }
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        picker.launch(arrayOf("image/*"))
    }
}
