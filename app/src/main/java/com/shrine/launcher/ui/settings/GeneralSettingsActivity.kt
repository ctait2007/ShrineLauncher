package com.shrine.launcher.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.shrine.launcher.R
import com.shrine.launcher.adb.AdbManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

class GeneralSettingsActivity : BaseSettingsActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var updateSubtitle: TextView? = null
    private var installJob: Job? = null

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

        val updateBtn = addButton("Check for Updates") { checkForUpdates() }
        updateSubtitle = updateBtn.findViewById(R.id.tvButtonSubtitle)
    }

    private fun checkForUpdates() {
        installJob?.cancel()
        updateSubtitle?.let { it.text = "Checking…"; it.visibility = View.VISIBLE }
        scope.launch {
            val tag = withContext(Dispatchers.IO) {
                runCatching {
                    JSONObject(URL("https://api.github.com/repos/ctait2007/ShrineLauncher/releases/latest")
                        .readText()).getString("tag_name").trimStart('v')
                }.getOrNull()
            }
            if (tag == null) {
                updateSubtitle?.text = "Could not check for updates"
                return@launch
            }
            val current = packageManager.getPackageInfo(packageName, 0).versionName
            if (tag == current) {
                updateSubtitle?.text = "Up to date — v$tag"
            } else {
                updateSubtitle?.text = "v$tag available"
                android.app.AlertDialog.Builder(this@GeneralSettingsActivity)
                    .setTitle("Update available")
                    .setMessage("v$tag is available (you have v$current)")
                    .setPositiveButton("Install") { _, _ -> installUpdate(tag) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun installUpdate(tag: String) {
        val url = "https://github.com/ctait2007/ShrineLauncher/releases/download/v$tag/shrine-v$tag-beta.apk"
        installJob = scope.launch {
            val adb = AdbManager.getInstance(this@GeneralSettingsActivity)
            val tmpPath = "/data/local/tmp/shrine_install.apk"
            val useTmp = adb.isConnected() &&
                adb.executeShell("touch $tmpPath && chmod 666 $tmpPath").exitCode == 0
            val dest = if (useTmp) java.io.File(tmpPath)
                       else java.io.File(cacheDir, "shrine_install.apk")

            updateSubtitle?.let { it.text = "Downloading…"; it.visibility = View.VISIBLE }

            val downloaded = withContext(Dispatchers.IO) {
                try {
                    val conn = URL(url).openConnection() as java.net.HttpURLConnection
                    conn.instanceFollowRedirects = true; conn.connect()
                    val total = conn.contentLength; var done = 0
                    conn.inputStream.use { inp ->
                        java.io.FileOutputStream(dest).use { out ->
                            val buf = ByteArray(8192); var n: Int
                            while (inp.read(buf).also { n = it } != -1) {
                                out.write(buf, 0, n); done += n
                                if (total > 0) {
                                    val pct = done * 100 / total
                                    withContext(Dispatchers.Main) { updateSubtitle?.text = "Downloading… $pct%" }
                                }
                            }
                        }
                    }
                    conn.disconnect(); true
                } catch (e: Exception) { false }
            }

            if (!downloaded) { updateSubtitle?.text = "Download failed — check connection"; return@launch }

            // Schedule restart BEFORE pm install — the install kills our process before
            // we can set any alarm afterwards. 60 s is a safe upper bound for the install.
            com.shrine.launcher.util.scheduleRestart(this@GeneralSettingsActivity, 60_000L)

            updateSubtitle?.text = "Installing…"
            if (useTmp) {
                val result = adb.executeShell("pm install -r $tmpPath", 60_000L)
                adb.executeShell("rm -f $tmpPath")
                val ok = result.exitCode == 0 || result.output.contains("Success", ignoreCase = true)
                if (ok) {
                    // Process usually dies before reaching here; if it hasn't, shorten alarm.
                    com.shrine.launcher.util.scheduleRestart(this@GeneralSettingsActivity, 3_000L)
                    updateSubtitle?.text = "✓ Installed — restarting…"
                    delay(500)
                    android.os.Process.killProcess(android.os.Process.myPid())
                } else {
                    com.shrine.launcher.util.cancelRestart(this@GeneralSettingsActivity)
                    updateSubtitle?.text = "Install failed: ${result.output}"
                }
            } else {
                updateSubtitle?.text = "Opening system installer…"
                try {
                    val fileUri = androidx.core.content.FileProvider.getUriForFile(
                        this@GeneralSettingsActivity, "$packageName.fileprovider", dest)
                    startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(fileUri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (e: Exception) {
                    updateSubtitle?.text = "Install failed: ${e.message}"
                }
            }
        }
    }

    override fun onDestroy() { super.onDestroy(); scope.cancel() }
}
