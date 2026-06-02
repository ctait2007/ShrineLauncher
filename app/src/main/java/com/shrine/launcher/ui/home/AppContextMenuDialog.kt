package com.shrine.launcher.ui.home

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import android.widget.TextView
import com.shrine.launcher.R
import com.shrine.launcher.adb.AdbManager
import com.shrine.launcher.data.model.AppInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AppContextMenuDialog(
    context: Context,
    private val app: AppInfo,
    private val isFavourite: Boolean,
    private val onFavouriteToggle: () -> Unit,
    private val onLaunch: () -> Unit
) : Dialog(context) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_app_context_menu)
        window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setGravity(Gravity.CENTER)
            setLayout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        }

        findViewById<ImageView>(R.id.ivAppIconMenu).setImageDrawable(app.icon)
        findViewById<TextView>(R.id.tvAppNameMenu).text = app.label

        val btnOpen    = findViewById<TextView>(R.id.btnOpen)
        val btnFav     = findViewById<TextView>(R.id.btnToggleFavourite)
        val btnInfo    = findViewById<TextView>(R.id.btnAppInfo)
        val btnStop    = findViewById<TextView>(R.id.btnForceStop)
        val btnUninstall = findViewById<TextView>(R.id.btnUninstall)
        val btnCancel  = findViewById<TextView>(R.id.btnCancel)

        btnFav.text = if (isFavourite) "Remove from Favourites" else "Add to Favourites"

        listOf(btnOpen, btnFav, btnInfo, btnStop, btnUninstall, btnCancel).forEach { btn ->
            btn.isFocusable = true
            btn.setOnFocusChangeListener { v, hasFocus -> applyMenuFocus(v as TextView, hasFocus) }
        }

        setOnDismissListener { scope.cancel() }

        btnOpen.setOnClickListener      { onLaunch(); dismiss() }
        btnFav.setOnClickListener       { onFavouriteToggle(); dismiss() }

        btnInfo.setOnClickListener {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", app.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            dismiss()
        }

        btnStop.setOnClickListener { forceStopApp(app.packageName) }

        btnUninstall.setOnClickListener {
            context.startActivity(
                Intent(Intent.ACTION_DELETE).apply {
                    data = Uri.fromParts("package", app.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            dismiss()
        }

        btnCancel.setOnClickListener { dismiss() }
    }

    private fun forceStopApp(packageName: String) {
        val adb = AdbManager.getInstance(context)
        if (adb.state != AdbManager.AdbState.CONNECTED) {
            Toast.makeText(context, "Connect ADB in Settings → ADB Shell to use Force Stop", Toast.LENGTH_LONG).show()
            dismiss()
            return
        }
        scope.launch {
            val result = adb.executeShell("pm force-stop $packageName")
            Toast.makeText(context,
                if (result.exitCode == 0) "${app.label} stopped"
                else "Force stop failed: ${result.output}",
                if (result.exitCode == 0) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
            dismiss()
        }
    }

    private fun applyMenuFocus(v: TextView, hasFocus: Boolean) {
        val density = v.resources.displayMetrics.density
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(if (hasFocus) 0x22E53935.toInt() else 0x00000000)
            setStroke(if (hasFocus) (2 * density).toInt() else 0,
                if (hasFocus) 0xFFFFFFFF.toInt() else 0x00000000)
            cornerRadius = 8 * density
        }
        v.background = bg
        v.setTextColor(if (hasFocus) 0xFFFFFFFF.toInt() else 0xFFB0B0B0.toInt())
    }
}
