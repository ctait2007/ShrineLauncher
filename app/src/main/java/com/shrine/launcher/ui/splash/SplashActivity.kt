package com.shrine.launcher.ui.splash

import android.content.Intent
import android.net.Uri
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
    private var transitioning = false

    // Safety-net timeout: if onCompletion never fires (decoder issue, corrupt
    // file, etc.) this guarantees we still reach the launcher.
    private val TIMEOUT_MS = 10_000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Skip animation on subsequent home-button presses within the same
        // process lifetime (see companion object comment below).
        if (splashComplete) {
            goHome()
            return
        }
        splashComplete = true

        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uri = Uri.parse("android.resource://$packageName/${R.raw.splash_animation}")

        binding.videoSplash.apply {
            setVideoURI(uri)
            setOnCompletionListener { goHome() }
            setOnErrorListener { _, _, _ -> goHome(); true }
            start()
        }

        // Belt-and-suspenders: transition after timeout regardless
        handler.postDelayed({ goHome() }, TIMEOUT_MS)
    }

    // Fired when the HOME intent re-targets this singleTask while it is
    // already on screen (home button pressed mid-animation).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        stopVideo()
        goHome()
    }

    private fun stopVideo() {
        if (::binding.isInitialized) binding.videoSplash.stopPlayback()
    }

    private fun goHome() {
        if (transitioning || isFinishing || isDestroyed) return
        transitioning = true
        handler.removeCallbacksAndMessages(null)
        stopVideo()
        startActivity(Intent(this, HomeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        stopVideo()
    }

    companion object {
        // True once the animation has played this process lifetime.
        // Resets on process death → splash replays on next cold start.
        @Volatile private var splashComplete = false
    }
}
