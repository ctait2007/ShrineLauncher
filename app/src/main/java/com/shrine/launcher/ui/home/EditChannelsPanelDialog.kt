package com.shrine.launcher.ui.home

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
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
import com.shrine.launcher.data.model.ChannelType
import com.shrine.launcher.data.model.LauncherRow
import com.shrine.launcher.data.model.RowKind
import com.shrine.launcher.data.repository.PreferencesRepository
import com.shrine.launcher.data.repository.TvContentRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EditChannelsPanelDialog(
    context: Context,
    private val wallpaperUri: String? = null
) : Dialog(context) {

    private val prefRepo by lazy { PreferencesRepository.getInstance(context) }
    private val tvRepo   by lazy { TvContentRepository.getInstance(context) }
    private val scope    = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_settings_panel)
        configureWindow()
        setupHeader()
        buildList()
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
        tvDate.text = "Edit Channels"
        findViewById<ImageView>(R.id.ivPanelLogo).visibility = View.GONE
    }

    private fun buildList() {
        scope.launch {
            val tvChannels = withContext(Dispatchers.IO) { tvRepo.listTvProviderChannels() }
            val rows       = prefRepo.loadRows()
            val helper     = SettingsPanelDialog(context)
            val body       = findViewById<LinearLayout>(R.id.panelBody)
            body.removeAllViews()
            val inflater   = LayoutInflater.from(context)

            // Grouped channel rows (Continue Watching, Watch Next, etc.)
            helper.addSectionHeader(body, "GROUPED CHANNELS")
            rows.filter { it.kind == RowKind.CHANNEL && it.channelType != ChannelType.TV_PROVIDER }
                .forEach { row -> addChannelEntry(body, inflater, helper, row) }

            // Individual TvProvider channels
            helper.addSectionHeader(body, "APP CHANNELS")
            if (tvChannels.isEmpty()) {
                val tv = TextView(context)
                tv.text = "No app channels found"
                tv.setTextColor(0xFF555555.toInt())
                tv.textSize = 13f
                tv.setPadding(48, 8, 48, 8)
                body.addView(tv)
            } else {
                tvChannels.forEach { (channelId, channelName, _) ->
                    val existingRow = rows.find {
                        it.channelType == ChannelType.TV_PROVIDER && it.tvProviderChannelId == channelId
                    }
                    val v = inflater.inflate(R.layout.item_panel_menu_entry, body, false)
                    val tvLabel = v.findViewById<TextView>(R.id.tvEntryLabel)
                    val ivIcon  = v.findViewById<ImageView>(R.id.ivEntryIcon)
                    val ivArrow = v.findViewById<ImageView>(R.id.ivEntryArrow)
                    tvLabel.text = channelName
                    ivIcon.visibility = View.GONE
                    ivArrow.visibility = View.GONE
                    if (existingRow == null || !existingRow.isVisible) tvLabel.alpha = 0.5f
                    helper.applyFocusBehavior(v, tvLabel, null)
                    v.setOnClickListener {
                        val row = existingRow ?: run {
                            val newRow = LauncherRow(
                                id = "row_tvprovider_$channelId",
                                title = channelName,
                                kind = RowKind.CHANNEL,
                                channelType = ChannelType.TV_PROVIDER,
                                tvProviderChannelId = channelId,
                                isVisible = false
                            )
                            prefRepo.addRow(newRow)
                            newRow
                        }
                        ChannelEditorDialog(context, row) { buildList() }.show()
                    }
                    body.addView(v)
                }
            }
        }
    }

    private fun addChannelEntry(
        body: LinearLayout,
        inflater: LayoutInflater,
        helper: SettingsPanelDialog,
        row: LauncherRow
    ) {
        val v = inflater.inflate(R.layout.item_panel_menu_entry, body, false)
        val tvLabel = v.findViewById<TextView>(R.id.tvEntryLabel)
        val ivIcon  = v.findViewById<ImageView>(R.id.ivEntryIcon)
        val ivArrow = v.findViewById<ImageView>(R.id.ivEntryArrow)
        tvLabel.text = row.title
        ivIcon.visibility = View.GONE
        ivArrow.visibility = View.GONE
        if (!row.isVisible) tvLabel.alpha = 0.5f
        helper.applyFocusBehavior(v, tvLabel, null)
        v.setOnClickListener { ChannelEditorDialog(context, row) { buildList() }.show() }
        body.addView(v)
    }

    override fun onStop() { super.onStop(); scope.cancel() }
    override fun onBackPressed() { dismiss() }
}
