package com.shrine.launcher.ui.splash

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.shrine.launcher.R
import com.shrine.launcher.databinding.ActivitySplashBinding
import com.shrine.launcher.ui.home.HomeActivity

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private val handler = Handler(Looper.getMainLooper())
    private val frames = arrayOfNulls<Bitmap>(FRAME_COUNT)
    private var frameIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If the animation already played this process lifetime, skip straight to the launcher.
        if (splashComplete) {
            goHome()
            return
        }
        splashComplete = true

        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Extract frames on a background thread so the main thread stays responsive,
        // then kick off the frame scheduler once extraction is done.
        Thread {
            extractFrames()
            runOnUiThread { scheduleFrame() }
        }.start()
    }

    // Called when the HOME intent re-targets this singleTask activity while it is
    // already running (e.g. home button pressed mid-animation).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handler.removeCallbacksAndMessages(null)
        goHome()
    }

    // ── Frame extraction ──────────────────────────────────────────────────────

    private fun extractFrames() {
        val opts = BitmapFactory.Options().apply {
            inScaled = false                        // load at native pixel size
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val sheet = BitmapFactory.decodeResource(resources, R.drawable.splash_sprite_sheet, opts)
            ?: return   // sprite sheet missing — animation will be skipped

        val frameW = sheet.width / COLS
        val frameH = sheet.height / ROWS

        var idx = 0
        outer@ for (row in 0 until ROWS) {
            for (col in 0 until COLS) {
                if (idx >= FRAME_COUNT) break@outer
                frames[idx++] = Bitmap.createBitmap(sheet, col * frameW, row * frameH, frameW, frameH)
            }
        }
        sheet.recycle()
    }

    // ── Animation scheduler ───────────────────────────────────────────────────

    private fun scheduleFrame() {
        if (frameIndex >= FRAME_COUNT) {
            goHome()
            return
        }
        frames[frameIndex]?.let { binding.ivSplash.setImageBitmap(it) }
        val duration = FRAME_DURATIONS_MS[frameIndex]
        frameIndex++
        handler.postDelayed({ scheduleFrame() }, duration)
    }

    // ── Transition ────────────────────────────────────────────────────────────

    private fun goHome() {
        startActivity(Intent(this, HomeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        finish()
    }

    // ── Lifecycle cleanup ─────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        frames.forEach { it?.recycle() }
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        private const val FRAME_COUNT = 16
        private const val COLS        = 4
        private const val ROWS        = 4

        // Per-frame display durations in milliseconds (total: 4750ms).
        private val FRAME_DURATIONS_MS = longArrayOf(
            250L, 250L, 250L, 250L,   // frames  1-4
            300L, 300L,               // frames  5-6
            250L, 250L, 250L, 250L,   // frames  7-10
            200L, 200L, 200L, 200L,   // frames 11-14
            300L, 1050L               // frames 15-16
        )

        // Survives configuration changes within this process lifetime.
        // Resets when the process is killed, which causes the splash to replay
        // on the next cold start — intentional behaviour.
        @Volatile private var splashComplete = false
    }
}
