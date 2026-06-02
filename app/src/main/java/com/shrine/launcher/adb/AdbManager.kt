package com.shrine.launcher.adb

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * ADB client for Fire TV / legacy TCP ADB (non-TLS, port 5555).
 *
 * The device uses classic ADB-over-TCP with RSA key authentication.
 * No SPAKE2 pairing needed — adbd shows a one-time "Allow ADB debugging?"
 * dialog on first connection with a new RSA key, approved via the remote.
 *
 * Commands execute as uid=2000(shell).
 */
class AdbManager private constructor(context: Context) : AbsAdbConnectionManager() {

    enum class AdbState { DISCONNECTED, CONNECTING, CONNECTED }

    companion object {
        const val DEFAULT_HOST = "localhost"
        const val DEFAULT_PORT = 5555

        @Volatile private var instance: AdbManager? = null
        fun getInstance(context: Context): AdbManager =
            instance ?: synchronized(this) {
                instance ?: AdbManager(context.applicationContext).also { instance = it }
            }
    }

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("shrine_adb", Context.MODE_PRIVATE)

    private val keyFile  = File(appContext.filesDir, "adb_private.key")
    private val certFile = File(appContext.filesDir, "adb_cert.der")

    private val bcProvider = BouncyCastleProvider()

    private lateinit var rsaPrivateKey: PrivateKey
    private lateinit var rsaCertificate: X509Certificate

    var state: AdbState = AdbState.DISCONNECTED
        private set

    init {
        if (keyFile.exists() && certFile.exists()) {
            rsaPrivateKey  = loadPrivateKey()
            rsaCertificate = loadCertificate()
        } else {
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            rsaPrivateKey  = kp.private
            rsaCertificate = buildSelfSignedCert(kp.private, kp.public)
            keyFile.writeBytes(rsaPrivateKey.encoded)
            certFile.writeBytes(rsaCertificate.encoded)
        }
        setApi(Build.VERSION.SDK_INT)
        setTimeout(10, TimeUnit.SECONDS)
    }

    // ── AbsAdbConnectionManager contract ──────────────────────────────────────

    override fun getPrivateKey(): PrivateKey    = rsaPrivateKey
    override fun getCertificate(): Certificate = rsaCertificate
    override fun getDeviceName(): String        = "Shrine Launcher"

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Connect to adbd via legacy TCP ADB (non-TLS).
     * On first use, adbd will display "Allow ADB debugging?" on screen — approve with the remote.
     */
    suspend fun doConnect(host: String = DEFAULT_HOST, port: Int = DEFAULT_PORT): ConnectResult =
        withContext(Dispatchers.IO) {
            state = AdbState.CONNECTING
            try {
                val ok = connect(host, port)
                if (ok) {
                    prefs.edit().putString("adb_host", host).putInt("adb_port", port).apply()
                    state = AdbState.CONNECTED
                    ConnectResult(success = true)
                } else {
                    state = AdbState.DISCONNECTED
                    ConnectResult(success = false,
                        error = "Connection refused — ensure ADB debugging is on and try approving the dialog on screen")
                }
            } catch (e: Exception) {
                state = AdbState.DISCONNECTED
                ConnectResult(success = false, error = e.message ?: "Unknown error")
            }
        }

    fun doDisconnect() {
        try { disconnect() } catch (_: Exception) {}
        state = AdbState.DISCONNECTED
    }

    /** Execute [command] as uid=2000(shell). Returns combined stdout+stderr. */
    suspend fun executeShell(command: String): ShellResult =
        withContext(Dispatchers.IO) {
            if (!isConnected()) return@withContext ShellResult("Not connected", -1)
            try {
                val stream: AdbStream = openStream("shell:$command")
                val output = stream.openInputStream().bufferedReader().readText().trim()
                stream.close()
                ShellResult(output, 0)
            } catch (e: Exception) {
                state = AdbState.DISCONNECTED
                ShellResult("Connection lost: ${e.message}", -1)
            }
        }

    fun savedHost(): String = prefs.getString("adb_host", DEFAULT_HOST) ?: DEFAULT_HOST
    fun savedPort(): Int    = prefs.getInt("adb_port", DEFAULT_PORT)

    // ── Data classes ───────────────────────────────────────────────────────────

    data class ConnectResult(val success: Boolean, val error: String? = null)
    data class ShellResult(val output: String, val exitCode: Int)

    // ── Key / cert helpers ─────────────────────────────────────────────────────

    private fun buildSelfSignedCert(privateKey: PrivateKey, publicKey: java.security.PublicKey): X509Certificate {
        val now    = Date()
        val expiry = Date(now.time + 10L * 365 * 24 * 60 * 60 * 1000)
        val name   = X500Name("CN=Shrine Launcher ADB")
        val builder = JcaX509v3CertificateBuilder(
            name, BigInteger.valueOf(System.currentTimeMillis()),
            now, expiry, name, publicKey)
        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(bcProvider).build(privateKey)
        return JcaX509CertificateConverter().setProvider(bcProvider).getCertificate(builder.build(signer))
    }

    private fun loadPrivateKey(): PrivateKey =
        KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))

    private fun loadCertificate(): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(certFile.inputStream()) as X509Certificate
}
