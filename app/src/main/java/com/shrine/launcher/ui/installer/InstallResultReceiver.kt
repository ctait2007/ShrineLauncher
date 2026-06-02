package com.shrine.launcher.ui.installer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val name   = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: ""
        when (status) {
            PackageInstaller.STATUS_SUCCESS ->
                Toast.makeText(context, "Installed successfully", Toast.LENGTH_SHORT).show()
            PackageInstaller.STATUS_FAILURE_ABORTED -> { /* user cancelled */ }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Toast.makeText(context, "Install failed: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
