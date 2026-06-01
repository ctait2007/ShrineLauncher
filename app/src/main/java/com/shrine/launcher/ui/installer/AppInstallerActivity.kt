package com.shrine.launcher.ui.installer

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.shrine.launcher.R
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class AppInstallerActivity : AppCompatActivity() {

    private lateinit var etUrl:         EditText
    private lateinit var btnInstallUrl: TextView
    private lateinit var btnPickApk:    TextView
    private lateinit var btnClose:      TextView
    private lateinit var tvStatus:      TextView
    private lateinit var progressBar:   ProgressBar

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val apkPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { installFromUri(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_installer)

        etUrl         = findViewById(R.id.etApkUrl)
        btnInstallUrl = findViewById(R.id.btnInstallFromUrl)
        btnPickApk    = findViewById(R.id.btnPickApk)
        btnClose      = findViewById(R.id.btnInstallerClose)
        tvStatus      = findViewById(R.id.tvInstallStatus)
        progressBar   = findViewById(R.id.progressInstall)

        listOf(btnInstallUrl, btnPickApk, btnClose).forEach { btn ->
            btn.isFocusable = true
            applyFocus(btn, false)
            btn.setOnFocusChangeListener { v, hasFocus -> applyFocus(v, hasFocus) }
        }

        btnInstallUrl.setOnClickListener {
            val url = etUrl.text.toString().trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                setStatus("Please enter a valid URL starting with http or https")
                return@setOnClickListener
            }
            installFromUrl(url)
        }

        btnPickApk.setOnClickListener {
            apkPicker.launch(arrayOf("application/vnd.android.package-archive", "*/*"))
        }

        btnClose.setOnClickListener { finish() }
    }

    override fun onDestroy() { super.onDestroy(); scope.cancel() }

    // ── Install from URL ──────────────────────────────────────────────────────

    private fun installFromUrl(urlString: String) {
        scope.launch {
            setLoading(true, "Downloading…")
            val apkFile = withContext(Dispatchers.IO) { downloadApk(urlString) }
            if (apkFile != null) {
                installApkFile(apkFile)
            } else {
                setStatus("Download failed — check the URL and try again")
                setLoading(false)
            }
        }
    }

    private suspend fun downloadApk(urlString: String): File? {
        val destFile = File(cacheDir, "downloaded.apk")
        return try {
            val conn = URL(urlString).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connect()
            val total = conn.contentLength
            var downloaded = 0
            conn.inputStream.use { input ->
                FileOutputStream(destFile).use { output ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        output.write(buf, 0, n)
                        downloaded += n
                        if (total > 0) {
                            val pct = downloaded * 100 / total
                            withContext(Dispatchers.Main) { setLoading(true, "Downloading… $pct%") }
                        }
                    }
                }
            }
            conn.disconnect()
            destFile
        } catch (e: Exception) {
            android.util.Log.e("AppInstaller", "Download failed: ${e.message}", e)
            null
        }
    }

    // ── Install from local file ───────────────────────────────────────────────

    private fun installFromUri(uri: Uri) {
        scope.launch {
            setLoading(true, "Reading file…")
            val apkFile = withContext(Dispatchers.IO) {
                try {
                    val dest = File(cacheDir, "picked.apk")
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(dest).use { input.copyTo(it) }
                    }
                    dest
                } catch (e: Exception) { null }
            }
            if (apkFile != null) installApkFile(apkFile)
            else { setStatus("Failed to read APK file"); setLoading(false) }
        }
    }

    // ── Core install ─────────────────────────────────────────────────────────

    private suspend fun installApkFile(apkFile: File) {
        setLoading(true, "Installing…")

        val hasPermission = checkSelfPermission(
            android.Manifest.permission.WRITE_SECURE_SETTINGS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            val result = withContext(Dispatchers.IO) {
                try {
                    val process  = Runtime.getRuntime().exec(
                        arrayOf("pm", "install", "-r", apkFile.absolutePath))
                    val stdout   = process.inputStream.bufferedReader().readText()
                    val stderr   = process.errorStream.bufferedReader().readText()
                    val exitCode = process.waitFor()
                    android.util.Log.d("AppInstaller",
                        "pm install exit=$exitCode out=$stdout err=$stderr")
                    exitCode == 0 || stdout.contains("Success", ignoreCase = true)
                } catch (e: Exception) {
                    android.util.Log.w("AppInstaller", "pm install threw: ${e.message}")
                    false
                }
            }

            setLoading(false)
            if (result) {
                setStatus("✓ Installed successfully")
                apkFile.delete()
                return
            }
        }

        setLoading(false)
        setStatus(if (hasPermission) "Silent install failed — opening system installer"
                  else "Opening system installer (grant WRITE_SECURE_SETTINGS for silent install)")
        try {
            val fileUri = androidx.core.content.FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", apkFile)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(fileUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            setStatus("Install failed: ${e.message}")
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun setLoading(loading: Boolean, message: String = "") {
        progressBar.visibility   = if (loading) View.VISIBLE else View.GONE
        btnInstallUrl.isEnabled  = !loading
        btnPickApk.isEnabled     = !loading
        if (message.isNotBlank()) tvStatus.text = message
    }

    private fun setStatus(msg: String) { tvStatus.text = msg }

    private fun applyFocus(v: View, hasFocus: Boolean) {
        val dp = v.resources.displayMetrics.density
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0x00000000)
            setStroke(if (hasFocus) (2 * dp).toInt() else (1 * dp).toInt(),
                if (hasFocus) 0xFFE53935.toInt() else 0xFFFFFFFF.toInt())
            cornerRadius = 8 * dp
        }
        v.background = bg
    }
}
