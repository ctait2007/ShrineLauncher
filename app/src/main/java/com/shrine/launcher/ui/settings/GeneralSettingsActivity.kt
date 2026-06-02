package com.shrine.launcher.ui.settings

import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
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

        addButton("Set as Default Launcher") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val rm = getSystemService(RoleManager::class.java)
                if (rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 0)
                } else {
                    Toast.makeText(this, "Already set as default launcher", Toast.LENGTH_SHORT).show()
                }
            } else {
                startActivity(Intent(android.provider.Settings.ACTION_HOME_SETTINGS))
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
