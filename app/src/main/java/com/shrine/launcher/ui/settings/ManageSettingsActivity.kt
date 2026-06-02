package com.shrine.launcher.ui.settings

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.shrine.launcher.util.ConfigManager

class ManageSettingsActivity : BaseSettingsActivity() {

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val ok = ConfigManager.exportToUri(this, uri)
        Toast.makeText(this,
            if (ok) "Settings exported" else "Export failed",
            Toast.LENGTH_SHORT).show()
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val ok = ConfigManager.importFromUri(this, uri)
        Toast.makeText(this,
            if (ok) "Settings imported" else "Import failed — invalid file",
            Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupBase("Manage Settings", intent.getStringExtra("wallpaper_uri"))

        addButton("Export settings") {
            exportLauncher.launch("shrine_settings.json")
        }

        addButton("Import settings") {
            importLauncher.launch(arrayOf("application/json", "*/*"))
        }

        addButton("Reset to defaults", textColor = 0xFFCF6679.toInt()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Reset to defaults?")
                .setMessage("All settings and row configuration will be reset. This cannot be undone.")
                .setPositiveButton("Reset") { _, _ ->
                    prefRepo.savePrefs(com.shrine.launcher.data.model.LauncherPrefs())
                    Toast.makeText(this, "Reset complete", Toast.LENGTH_SHORT).show()
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
}
