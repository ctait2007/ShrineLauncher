package com.shrine.launcher.ui.splash

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import com.shrine.launcher.R
import com.shrine.launcher.databinding.ActivitySplashBinding

/**
 * Full-screen video overlay launched by HomeActivity on cold start.
 * HomeActivity loads beneath it; when the video ends this activity
 * simply finishes, revealing the already-loaded launcher instantly.
 *
 * All key/remote input is consumed while the splash is visible.
 */
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private val handler = Handler(Looper.getMainLooper())
    private var dismissed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uri = Uri.parse("android.resource://$packageName/${R.raw.splash_animation}")
        binding.videoSplash.apply {
            setVideoURI(uri)
            setOnCompletionListener { dismiss() }
            setOnErrorListener { _, _, _ -> dismiss(); true }
            start()
        }

        // Safety net — dismiss regardless if video never fires onCompletion
        handler.postDelayed({ dismiss() }, 10_000L)
    }

    // Consume every key/remote event — the launcher beneath must not react
    // to anything while the splash is playing.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = true

    private fun dismiss() {
        if (dismissed || isFinishing || isDestroyed) return
        dismissed = true
        handler.removeCallbacksAndMessages(null)
        if (::binding.isInitialized) binding.videoSplash.stopPlayback()
        // No-animation finish so the reveal of HomeActivity is instant
        overridePendingTransition(0, 0)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        if (::binding.isInitialized) binding.videoSplash.stopPlayback()
    }
}
