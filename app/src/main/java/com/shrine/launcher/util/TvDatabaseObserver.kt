package com.shrine.launcher.util

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper

/**
 * Debounced ContentObserver that fires whenever any streaming app updates
 * its TvContract rows (new Continue Watching entry, progress update, etc.)
 * 500ms debounce prevents flooding the UI with rapid successive updates.
 */
class TvDatabaseObserver(
    private val onDataChanged: () -> Unit
) : ContentObserver(Handler(Looper.getMainLooper())) {

    private val handler  = Handler(Looper.getMainLooper())
    private val debounce = Runnable { onDataChanged() }

    override fun onChange(selfChange: Boolean) {
        super.onChange(selfChange)
        handler.removeCallbacks(debounce)
        handler.postDelayed(debounce, 500)
    }
}
