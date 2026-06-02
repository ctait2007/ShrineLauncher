package com.shrine.launcher.ui.home

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.shrine.launcher.R
import com.shrine.launcher.data.model.ChannelType
import com.shrine.launcher.data.model.LauncherRow
import com.shrine.launcher.data.repository.AppRepository
import com.shrine.launcher.data.repository.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChannelEditorDialog(
    context: Context,
    private val row: LauncherRow,
    private val onSaved: () -> Unit
) : Dialog(context) {

    private val prefRepo by lazy { PreferencesRepository.getInstance(context) }
    private val scope    = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val sizeLabels = arrayOf<String?>(null, "S", "M", "L", "XL")
    private val sizeDisplayNames = arrayOf("Global", "S — Small", "M — Medium", "L — Large", "XL — Extra Large")
    private var currentSizeLabel: String? = row.iconSizeLabelOverride

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_channel_editor)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        }

        val tvEdTitle  = findViewById<TextView>(R.id.tvChEditorTitle)
        val etTitle    = findViewById<EditText>(R.id.etChTitle)
        val switchVis  = findViewById<Switch>(R.id.switchChVisible)
        val switchPin  = findViewById<Switch>(R.id.switchChPinned)
        val tvSize     = findViewById<TextView>(R.id.tvChSizeValue)
        val rowSource  = findViewById<LinearLayout>(R.id.rowChSourceFilter)
        val llHidden   = findViewById<LinearLayout>(R.id.llHiddenItems)
        val tvNoHidden = findViewById<TextView>(R.id.tvNoHiddenItems)

        tvEdTitle.text = row.title
        etTitle.setText(row.title)
        switchVis.isChecked = row.isVisible
        switchPin.isChecked = row.isPinnedToTop
        tvSize.text = sizeDisplayNames[sizeLabels.indexOfFirst { it == currentSizeLabel }.coerceAtLeast(0)]

        // Source filter only for grouped channel rows (not individual TvProvider channels)
        rowSource.visibility = if (row.channelType == ChannelType.TV_PROVIDER) View.GONE else View.VISIBLE

        setupFocus(findViewById<LinearLayout>(R.id.rowChVisible))
        setupFocus(findViewById<LinearLayout>(R.id.rowChPinned))
        setupFocus(findViewById<LinearLayout>(R.id.rowChSize))
        setupFocus(rowSource)
        setupFocus(findViewById<TextView>(R.id.btnChEditorCancel))
        setupFocus(findViewById<TextView>(R.id.btnChEditorSave))

        findViewById<LinearLayout>(R.id.rowChVisible).setOnClickListener {
            switchVis.isChecked = !switchVis.isChecked
        }
        findViewById<LinearLayout>(R.id.rowChPinned).setOnClickListener {
            switchPin.isChecked = !switchPin.isChecked
        }
        findViewById<LinearLayout>(R.id.rowChSize).setOnClickListener {
            val curr = sizeLabels.indexOfFirst { it == currentSizeLabel }.coerceAtLeast(0)
            AlertDialog.Builder(context)
                .setTitle("Icon Size")
                .setSingleChoiceItems(sizeDisplayNames, curr) { d, which ->
                    currentSizeLabel = sizeLabels[which]
                    tvSize.text = sizeDisplayNames[which]
                    d.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        rowSource.setOnClickListener { showSourceFilter() }

        loadHiddenItems(llHidden, tvNoHidden)

        findViewById<TextView>(R.id.btnChEditorCancel).setOnClickListener { dismiss() }
        findViewById<TextView>(R.id.btnChEditorSave).setOnClickListener {
            val updated = row.copy(
                title = etTitle.text.toString().ifBlank { row.title },
                isVisible = switchVis.isChecked,
                isPinnedToTop = switchPin.isChecked,
                iconSizeLabelOverride = currentSizeLabel
            )
            prefRepo.updateRow(updated)
            onSaved()
            dismiss()
        }
    }

    private fun loadHiddenItems(llHidden: LinearLayout, tvNoHidden: TextView) {
        val dismissedIds = prefRepo.getDismissedForChannel(row.id).toList()
        if (dismissedIds.isEmpty()) {
            tvNoHidden.visibility = View.VISIBLE
            llHidden.visibility = View.GONE
            return
        }
        tvNoHidden.visibility = View.GONE
        llHidden.visibility = View.VISIBLE
        llHidden.removeAllViews()
        dismissedIds.forEach { contentId ->
            val tv = TextView(context)
            tv.text = contentId
            tv.setTextColor(0xFFB0B0B0.toInt())
            tv.textSize = 13f
            tv.setPadding(0, 8, 0, 8)
            tv.isFocusable = true
            tv.isClickable = true
            tv.setOnClickListener {
                prefRepo.restoreDismissedForChannel(row.id, contentId)
                llHidden.removeView(tv)
                if (llHidden.childCount == 0) {
                    tvNoHidden.visibility = View.VISIBLE
                    llHidden.visibility = View.GONE
                }
            }
            llHidden.addView(tv)
        }
    }

    private fun showSourceFilter() {
        scope.launch {
            // Only show apps that actually publish TvProvider channels
            val tvChannels = withContext(Dispatchers.IO) {
                com.shrine.launcher.data.repository.TvContentRepository
                    .getInstance(context).listTvProviderChannels()
            }
            val channelPackages = tvChannels.map { it.third }.distinct()
            val allApps = withContext(Dispatchers.IO) {
                AppRepository.getInstance(context).getAllApps()
            }.filter { it.packageName in channelPackages }
            val pkgNames = allApps.map { it.packageName }.toTypedArray()
            val labels   = allApps.map { it.label }.toTypedArray()
            val allowed  = row.allowedPackages
            val checked  = BooleanArray(pkgNames.size) { i -> allowed.isEmpty() || pkgNames[i] in allowed }
            AlertDialog.Builder(context)
                .setTitle("Source filter")
                .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
                .setPositiveButton("Apply") { _, _ ->
                    val newAllowed = pkgNames.filterIndexed { i, _ -> checked[i] }
                    val allSelected = newAllowed.size == pkgNames.size
                    val updated = row.copy(allowedPackages = if (allSelected) emptyList() else newAllowed)
                    prefRepo.updateRow(updated)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun setupFocus(v: View) {
        val dp = v.resources.displayMetrics.density
        v.setOnFocusChangeListener { view, hasFocus ->
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(if (hasFocus) 0x1AFFFFFF.toInt() else 0x00000000)
                setStroke(
                    if (hasFocus) (2 * dp).toInt() else 0,
                    if (hasFocus) 0xFFE53935.toInt() else 0x00000000
                )
                cornerRadius = 8 * dp
            }
            view.background = bg
        }
    }

    override fun onStop() { super.onStop(); scope.cancel() }
    override fun onBackPressed() { dismiss() }
}
