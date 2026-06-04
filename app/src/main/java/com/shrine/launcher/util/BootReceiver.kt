package com.shrine.launcher.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.shrine.launcher.ui.home.HomeActivity

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // MY_PACKAGE_REPLACED fires in the NEW process right after a silent
                // pm-install-r update. Pending alarms are cancelled by the system
                // during package replacement, so this is the only reliable restart hook.
                val launch = Intent(context, HomeActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                context.startActivity(launch)
            }
        }
    }
}
