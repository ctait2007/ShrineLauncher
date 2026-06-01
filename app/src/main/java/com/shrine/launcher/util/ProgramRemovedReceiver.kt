package com.shrine.launcher.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.tvprovider.media.tv.TvContractCompat

/**
 * Receives ACTION_PREVIEW_PROGRAM_BROWSABLE_DISABLED when the user dismisses
 * a program card on the home screen. Deletes that program from TvProvider so
 * it doesn't reappear.
 */
class ProgramRemovedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            TvContractCompat.ACTION_PREVIEW_PROGRAM_BROWSABLE_DISABLED -> {
                val progId = intent.getLongExtra(
                    TvContractCompat.EXTRA_PREVIEW_PROGRAM_ID, -1L)
                if (progId >= 0) {
                    try {
                        context.contentResolver.delete(
                            TvContractCompat.buildPreviewProgramUri(progId), null, null)
                        Log.d("ProgramRemovedReceiver", "Deleted program $progId")
                    } catch (e: Exception) {
                        Log.w("ProgramRemovedReceiver", "Failed to delete program: ${e.message}")
                    }
                }
            }
            TvContractCompat.ACTION_WATCH_NEXT_PROGRAM_BROWSABLE_DISABLED -> {
                val progId = intent.getLongExtra(
                    TvContractCompat.EXTRA_WATCH_NEXT_PROGRAM_ID, -1L)
                if (progId >= 0) {
                    try {
                        context.contentResolver.delete(
                            TvContractCompat.buildWatchNextProgramUri(progId), null, null)
                        Log.d("ProgramRemovedReceiver", "Deleted watch-next $progId")
                    } catch (e: Exception) {
                        Log.w("ProgramRemovedReceiver", "Failed to delete watch-next: ${e.message}")
                    }
                }
            }
        }
    }
}
