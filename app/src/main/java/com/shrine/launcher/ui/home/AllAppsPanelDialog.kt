package com.shrine.launcher.ui.home

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.shrine.launcher.R
import com.shrine.launcher.data.model.AppInfo
import com.shrine.launcher.data.repository.AppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AllAppsPanelDialog(
    context: Context,
    private val wallpaperUri: String? = null
) : Dialog(context) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_settings_panel)
        configureWindow()
        setupHeader()
        loadApps()
    }

    private fun configureWindow() {
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.END or Gravity.FILL_VERTICAL)
            val w = (context.resources.displayMetrics.widthPixels * 0.30f).toInt()
            setLayout(w, WindowManager.LayoutParams.MATCH_PARENT)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
    }

    private fun setupHeader() {
        val tvDate = findViewById<TextView>(R.id.tvPanelDate)
        tvDate.text = "All Apps"
        findViewById<ImageView>(R.id.ivPanelLogo).visibility = View.GONE
    }

    private fun loadApps() {
        scope.launch {
            val apps = withContext(Dispatchers.IO) {
                AppRepository.getInstance(context).getAllApps()
            }
            buildList(apps)
        }
    }

    private fun buildList(apps: List<AppInfo>) {
        val body = findViewById<LinearLayout>(R.id.panelBody)
        body.removeAllViews()
        val inflater = LayoutInflater.from(context)
        apps.forEach { app ->
            val v = inflater.inflate(R.layout.item_all_apps_entry, body, false)
            v.findViewById<ImageView>(R.id.ivAppIcon).setImageDrawable(app.icon)
            val tvLabel = v.findViewById<TextView>(R.id.tvAppLabel)
            tvLabel.text = app.label
            applyFocus(v, tvLabel)
            v.setOnClickListener {
                AppRepository.getInstance(context).launchApp(app.packageName)
                dismiss()
            }
            v.setOnLongClickListener {
                // Will be wired to context menu from HomeActivity in Phase 4
                true
            }
            body.addView(v)
        }
    }

    private fun applyFocus(root: View, label: TextView) {
        val dp = root.resources.displayMetrics.density
        root.setOnFocusChangeListener { v, hasFocus ->
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(if (hasFocus) 0x1AFFFFFF.toInt() else 0x00000000)
                setStroke(
                    if (hasFocus) (2 * dp).toInt() else 0,
                    if (hasFocus) 0xFFFFFFFF.toInt() else 0x00000000
                )
                cornerRadius = 8 * dp
            }
            v.background = bg
            label.setTextColor(if (hasFocus) 0xFFFFFFFF.toInt() else 0xFFB0B0B0.toInt())
        }
    }

    override fun onStop() { super.onStop(); scope.cancel() }
    override fun onBackPressed() { dismiss() }
}
