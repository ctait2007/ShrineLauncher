package com.shrine.launcher.ui.home

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.shrine.launcher.R
import com.shrine.launcher.ui.installer.AppInstallerActivity
import com.shrine.launcher.ui.settings.ShrineSettingsActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsPanelDialog(
    context: Context,
    private val wallpaperUri: String? = null,
    private val onDismissed: (() -> Unit)? = null,
    private val onAppLongClick: ((com.shrine.launcher.data.model.AppInfo) -> Unit)? = null
) : Dialog(context) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_settings_panel)
        configureWindow()
        setupHeader()
        buildMenu()
        setOnDismissListener { onDismissed?.invoke() }
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
        tvDate.text = SimpleDateFormat("EEE MMM d, yyyy", Locale.getDefault()).format(Date())
    }

    private fun buildMenu() {
        val body = findViewById<LinearLayout>(R.id.panelBody)
        body.removeAllViews()

        addSectionHeader(body, "SETTINGS")
        addMenuItem(body, "All Apps", R.drawable.ic_grid, showArrow = false) {
            AllAppsPanelDialog(context, wallpaperUri, onAppLongClick).show()
        }
        addMenuItem(body, "Edit Categories", R.drawable.ic_settings_rows, showArrow = false) {
            EditCategoriesPanelDialog(context, wallpaperUri).show()
        }
        addMenuItem(body, "Edit Channels", R.drawable.ic_channel, showArrow = false) {
            EditChannelsPanelDialog(context, wallpaperUri).show()
        }
        addSeparator(body)
        var shrineSettingsView: View? = null
        shrineSettingsView = addMenuItem(body, "Shrine Settings", R.drawable.ic_settings, showArrow = true) {
            // Keep the panel open so back from ShrineSettingsActivity returns here
            context.startActivity(Intent(context, ShrineSettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                wallpaperUri?.let { putExtra("wallpaper_uri", it) }
            })
        }
        addMenuItem(body, "Android Settings", R.drawable.ic_android, showArrow = true) {
            dismiss()
            context.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
        addMenuItem(body, "Install 3rd Party Apps", R.drawable.ic_install, showArrow = true) {
            dismiss()
            context.startActivity(Intent(context, AppInstallerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    fun addSectionHeader(parent: LinearLayout, text: String) {
        val v = LayoutInflater.from(context).inflate(R.layout.item_panel_section_header, parent, false)
        (v as TextView).text = text
        parent.addView(v)
    }

    fun addSeparator(parent: LinearLayout) {
        val v = View(context)
        val dp = context.resources.displayMetrics.density
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt())
        lp.setMargins(0, 8, 0, 8)
        v.layoutParams = lp
        v.setBackgroundColor(0x22FFFFFF)
        parent.addView(v)
    }

    fun addMenuItem(
        parent: LinearLayout,
        label: String,
        iconRes: Int,
        showArrow: Boolean = false,
        onClick: () -> Unit
    ): View {
        val v = LayoutInflater.from(context).inflate(R.layout.item_panel_menu_entry, parent, false)
        val tvLabel = v.findViewById<TextView>(R.id.tvEntryLabel)
        val ivIcon  = v.findViewById<ImageView>(R.id.ivEntryIcon)
        val ivArrow = v.findViewById<ImageView>(R.id.ivEntryArrow)
        tvLabel.text = label
        try { ivIcon.setImageResource(iconRes) } catch (e: Exception) { ivIcon.visibility = View.GONE }
        ivArrow.visibility = if (showArrow) View.VISIBLE else View.GONE

        applyFocusBehavior(v, tvLabel, ivIcon)
        v.setOnClickListener { onClick() }
        parent.addView(v)
        return v
    }

    fun applyFocusBehavior(root: View, label: TextView, icon: ImageView?) {
        val dp = root.resources.displayMetrics.density
        root.setOnFocusChangeListener { v, hasFocus ->
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(if (hasFocus) 0x1AFFFFFF.toInt() else 0x00000000)
                setStroke(
                    if (hasFocus) (2 * dp).toInt() else 0,
                    if (hasFocus) 0xFFE53935.toInt() else 0x00000000
                )
                cornerRadius = 8 * dp
            }
            v.background = bg
            label.setTextColor(if (hasFocus) 0xFFFFFFFF.toInt() else 0xFFB0B0B0.toInt())
            icon?.setColorFilter(if (hasFocus) 0xFFFFFFFF.toInt() else 0xFFB0B0B0.toInt())
        }
    }

    override fun onBackPressed() {
        dismiss()
    }
}
