package com.shrine.launcher.ui.settings

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

class GeneralSettingsActivity : BaseSettingsActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupBase("General", intent.getStringExtra("wallpaper_uri"))
        val prefs = prefRepo.loadPrefs()

        addToggle("Enable Channels", "Show Continue Watching and Watch Next rows",
            isChecked = prefs.channelsEnabled) { checked ->
            saveAndRefresh { it.copy(channelsEnabled = checked) }
        }

        addButton("Set as Default Launcher",
            subtitle = "Opens system home-app picker") {
            // On Fire TV / Android TV, open the system's default-home selector.
            // We send a HOME intent so the OS shows the disambiguation dialog,
            // or fall back to ACTION_HOME_SETTINGS if the chooser doesn't appear.
            try {
                val pick = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(pick)
            } catch (e: Exception) {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_HOME_SETTINGS))
                } catch (e2: Exception) {
                    Toast.makeText(this,
                        "Open Settings → Applications → Default Apps → Home App",
                        Toast.LENGTH_LONG).show()
                }
            }
        }

        addButton("Check for Updates") { checkForUpdates() }
    }

    private fun checkForUpdates() {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val json = URL("https://api.github.com/repos/ctait2007/ShrineLauncher/releases/latest")
                        .readText()
                    JSONObject(json).getString("tag_name").trimStart('v')
                }.getOrNull()
            }
            if (result == null) {
                Toast.makeText(this@GeneralSettingsActivity,
                    "Could not check for updates", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val current = packageManager.getPackageInfo(packageName, 0).versionName
            if (result == current) {
                Toast.makeText(this@GeneralSettingsActivity,
                    "You're up to date (v$current)", Toast.LENGTH_SHORT).show()
            } else {
                android.app.AlertDialog.Builder(this@GeneralSettingsActivity)
                    .setTitle("Update available")
                    .setMessage("v$result is available (you have v$current)")
                    .setPositiveButton("Install") { _, _ -> launchInstaller(result) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun launchInstaller(version: String) {
        val url = "https://github.com/ctait2007/ShrineLauncher/releases/download/v$version/ShrineLauncher-debug.apk"
        val intent = Intent(this, com.shrine.launcher.ui.installer.AppInstallerActivity::class.java)
        intent.putExtra("install_url", url)
        startActivity(intent)
    }

    override fun onDestroy() { super.onDestroy(); scope.cancel() }
}
