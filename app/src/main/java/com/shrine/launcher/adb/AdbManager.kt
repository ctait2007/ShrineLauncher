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
import org.conscrypt.Conscrypt
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Security
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit

class AdbManager private constructor(context: Context) : AbsAdbConnectionManager() {

    enum class AdbState { NOT_PAIRED, PAIRING, PAIRED, CONNECTING, CONNECTED }

    companion object {
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

    // Use an explicit BC instance — avoids conflicting with Android's built-in BC provider
    private val bcProvider = BouncyCastleProvider()

    private lateinit var rsaPrivateKey: PrivateKey
    private lateinit var rsaCertificate: X509Certificate

    var state: AdbState = AdbState.NOT_PAIRED
        private set

    init {
        // Conscrypt for TLS (required by libadb-android on Android 9+)
        try { Security.insertProviderAt(Conscrypt.newProvider(), 1) } catch (_: Exception) {}

        // Load or generate RSA key pair + self-signed cert
        if (keyFile.exists() && certFile.exists()) {
            rsaPrivateKey  = loadPrivateKey()
            rsaCertificate = loadCertificate()
        } else {
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            rsaPrivateKey  = kp.private
            rsaCertificate = buildSelfSignedCert(kp.private, rsaPrivateKey.let { kp.public })
            keyFile.writeBytes(rsaPrivateKey.encoded)   // PKCS8 DER
            certFile.writeBytes(rsaCertificate.encoded) // X.509 DER
        }

        state = if (prefs.getBoolean("adb_paired", false)) AdbState.PAIRED else AdbState.NOT_PAIRED
        setApi(Build.VERSION.SDK_INT)
        setTimeout(10, TimeUnit.SECONDS)
    }

    // ── AbsAdbConnectionManager contract ──────────────────────────────────────

    override fun getPrivateKey(): PrivateKey    = rsaPrivateKey
    override fun getCertificate(): Certificate = rsaCertificate
    override fun getDeviceName(): String        = "Shrine Launcher"

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * SPAKE2 pairing with Android 11 Wireless Debugging.
     * [host] should be "localhost", [pairingPort] is shown under "Pair device with pairing code".
     */
    suspend fun doPair(host: String, pairingPort: Int, code: String): PairingResult =
        withContext(Dispatchers.IO) {
            state = AdbState.PAIRING
            try {
                val ok = pair(host, pairingPort, code)
                if (ok) {
                    prefs.edit()
                        .putBoolean("adb_paired", true)
                        .putString("adb_host", host)
                        .apply()
                    state = AdbState.PAIRED
                    PairingResult(success = true)
                } else {
                    state = AdbState.NOT_PAIRED
                    PairingResult(success = false, error = "Pairing failed — check code and port")
                }
            } catch (e: Exception) {
                state = AdbState.NOT_PAIRED
                PairingResult(success = false, error = e.message ?: "Unknown error")
            }
        }

    /**
     * Connect to adbd on [host]:[port] using stored keys.
     * The port is shown in Developer Options > Wireless Debugging (the main IP:port line).
     */
    suspend fun doConnect(host: String, port: Int): ConnectResult =
        withContext(Dispatchers.IO) {
            state = AdbState.CONNECTING
            try {
                val ok = connect(host, port)
                if (ok) {
                    prefs.edit().putString("adb_host", host).putInt("adb_port", port).apply()
                    state = AdbState.CONNECTED
                    ConnectResult(success = true)
                } else {
                    state = AdbState.PAIRED
                    ConnectResult(success = false, error = "Connection refused — check port and that Wireless Debugging is on")
                }
            } catch (e: Exception) {
                state = AdbState.PAIRED
                ConnectResult(success = false, error = e.message ?: "Unknown error")
            }
        }

    fun doDisconnect() {
        try { disconnect() } catch (_: Exception) {}
        state = if (prefs.getBoolean("adb_paired", false)) AdbState.PAIRED else AdbState.NOT_PAIRED
    }

    /** Execute [command] as uid=2000(shell) and return combined output. */
    suspend fun executeShell(command: String): ShellResult =
        withContext(Dispatchers.IO) {
            if (!isConnected()) return@withContext ShellResult("Not connected", -1)
            try {
                val stream: AdbStream = openStream("shell:$command")
                val output = stream.openInputStream().bufferedReader().readText().trim()
                stream.close()
                ShellResult(output, 0)
            } catch (e: Exception) {
                state = AdbState.PAIRED   // assume connection dropped
                ShellResult("Connection lost: ${e.message}", -1)
            }
        }

    fun resetPairing() {
        doDisconnect()
        prefs.edit().putBoolean("adb_paired", false).apply()
        state = AdbState.NOT_PAIRED
    }

    fun savedHost(): String = prefs.getString("adb_host", "localhost") ?: "localhost"
    fun savedPort(): Int    = prefs.getInt("adb_port", 5555)

    // ── Data classes ───────────────────────────────────────────────────────────

    data class PairingResult(val success: Boolean, val error: String? = null)
    data class ConnectResult(val success: Boolean, val error: String? = null)
    data class ShellResult(val output: String, val exitCode: Int)

    // ── Key / cert helpers ─────────────────────────────────────────────────────

    private fun buildSelfSignedCert(privateKey: PrivateKey, publicKey: java.security.PublicKey): X509Certificate {
        val now    = Date()
        val expiry = Date(now.time + 10L * 365 * 24 * 60 * 60 * 1000)
        val name   = X500Name("CN=Shrine Launcher ADB")
        val builder = JcaX509v3CertificateBuilder(
            name, BigInteger.valueOf(System.currentTimeMillis()),
            now, expiry, name, publicKey
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(bcProvider)
            .build(privateKey)
        return JcaX509CertificateConverter()
            .setProvider(bcProvider)
            .getCertificate(builder.build(signer))
    }

    private fun loadPrivateKey(): PrivateKey =
        KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))

    private fun loadCertificate(): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(certFile.inputStream()) as X509Certificate
}
