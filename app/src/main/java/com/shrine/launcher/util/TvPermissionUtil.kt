package com.shrine.launcher.util

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.shrine.launcher.data.repository.TvContentRepository

/**
 * Checks whether we can actually read TvContract data.
 * If a SecurityException is thrown, we have the permission declared but
 * not granted — direct the user to grant it via Notification Listener settings,
 * which on Fire TV is the mechanism used to elevate third-party launcher access.
 */
object TvPermissionUtil {

    private const val TAG = "TvPermissionUtil"

    fun checkAndRequestAccess(context: Context): Boolean {
        val cr = context.contentResolver
        return try {
            val cursor = cr.query(
                android.media.tv.TvContract.Channels.CONTENT_URI,
                arrayOf(android.media.tv.TvContract.Channels._ID),
                null, null, null
            )
            cursor?.close()
            Log.d(TAG, "TvContract access granted")
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "TvContract access denied — directing to settings: ${e.message}")
            try {
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e2: Exception) {
                Log.e(TAG, "Could not open settings: ${e2.message}")
            }
            false
        } catch (e: Exception) {
            Log.w(TAG, "TvContract query error: ${e.message}")
            false
        }
    }
}
