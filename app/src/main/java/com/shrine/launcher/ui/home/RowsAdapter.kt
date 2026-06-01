package com.shrine.launcher.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.shrine.launcher.R
import com.shrine.launcher.data.model.*

sealed class RowItem {
    data class CategoryRow(val row: LauncherRow, val apps: List<AppInfo>) : RowItem()
    data class ChannelRow(val row: LauncherRow, val content: List<TvContent>) : RowItem()
}

class RowsAdapter(
    private val onAppClick: (AppInfo) -> Unit,
    private val onAppLongClick: (AppInfo) -> Unit,
    private val onContentClick: (TvContent) -> Unit,
    private val onContentLongClick: (TvContent) -> Unit,
    private val onRowFocused: (String) -> Unit,
    private val onRowSettingsClick: (LauncherRow) -> Unit,
    private val onRowDisplayModeToggle: (LauncherRow) -> Unit,
    private val cornerRadiusPercent: Int = 50,
    private val iconSizeDp: Int = 88,
    private val rowStartPaddingDp: Int = 24,
    private val itemSpacingDp: Int = 50,
    private val rowSpacingDp: Int  = 24
) : ListAdapter<RowItem, RowsAdapter.RowViewHolder>(DIFF) {

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is RowItem.CategoryRow -> 0
        is RowItem.ChannelRow  -> 1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_row, parent, false)
        return RowViewHolder(v)
    }

    override fun onBindViewHolder(holder: RowViewHolder, position: Int) =
        holder.bind(getItem(position))

    inner class RowViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvLabel:        TextView     = view.findViewById(R.id.tvRowLabel)
        private val tvKind:         TextView     = view.findViewById(R.id.tvRowKind)
        private val rvApps:         RecyclerView = view.findViewById(R.id.rvApps)
        private val btnRowSettings: ImageButton  = view.findViewById(R.id.btnRowSettings)
        private val btnDisplayMode: ImageButton  = view.findViewById(R.id.btnToggleDisplayMode)
        private val btnIconSize:    ImageButton  = view.findViewById(R.id.btnIconSize)
        private val sidePanel:   View = view.findViewById(R.id.sidePanel)
        private val rowContent:  View = view.findViewById(R.id.rowContent)
        private val emptyState: View = view.findViewById(R.id.emptyState)
        private val tvEmptyMsg: android.widget.TextView = view.findViewById(R.id.tvEmptyMessage)

        private var panelOpen   = false
        private var isChannel   = false

        fun bind(item: RowItem) {
            val row = when (item) {
                is RowItem.CategoryRow -> item.row
                is RowItem.ChannelRow  -> item.row
            }
            val dp = itemView.resources.displayMetrics.density

            tvLabel.text = row.title
            isChannel    = item is RowItem.ChannelRow

            panelOpen = false
            sidePanel.translationX     = -136f * dp
            rowContent.translationX    = 0f
            sidePanel.visibility       = View.INVISIBLE
            btnRowSettings.visibility  = View.GONE
            btnDisplayMode.visibility  = View.GONE
            btnIconSize.visibility     = View.GONE
            btnRowSettings.isFocusable = false
            btnDisplayMode.isFocusable = false
            btnIconSize.isFocusable    = false

            // Apply row bottom spacing
            val rowSpacingPx = (rowSpacingDp * dp).toInt()
            (rowContent.layoutParams as? ViewGroup.MarginLayoutParams)
                ?.bottomMargin = rowSpacingPx
            rowContent.requestLayout()

            when (row.kind) {
                RowKind.CHANNEL -> {
                    tvKind.text = "CHANNEL"
                    tvKind.setBackgroundResource(R.drawable.bg_kind_badge_channel)
                }
                RowKind.CATEGORY -> {
                    tvKind.text = "APPS"
                    tvKind.setBackgroundResource(R.drawable.bg_kind_badge_category)
                }
            }

            btnDisplayMode.setImageResource(
                if (row.cardDisplayMode == CardDisplayMode.BANNER) R.drawable.ic_banner
                else R.drawable.ic_grid
            )

            btnRowSettings.setOnClickListener { onRowSettingsClick(row) }
            btnDisplayMode.setOnClickListener { onRowDisplayModeToggle(row) }
            btnIconSize.setOnClickListener {
                val sizes  = arrayOf("Global (default)", "S — Small", "M — Medium", "L — Large", "XL — Extra Large")
                val labels = arrayOf<String?>(null, "S", "M", "L", "XL")
                val current = labels.indexOfFirst { it == row.iconSizeLabelOverride }.coerceAtLeast(0)
                android.app.AlertDialog.Builder(itemView.context)
                    .setTitle("Icon Size")
                    .setSingleChoiceItems(sizes, current) { dialog, which ->
                        onRowSettingsClick(row.copy(iconSizeLabelOverride = labels[which]))
                        dialog.dismiss()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }

            listOf(btnRowSettings, btnDisplayMode, btnIconSize).forEach { btn ->
                btn.setOnFocusChangeListener { v, hasFocus ->
                    val dp2 = v.resources.displayMetrics.density
                    val bg = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        setColor(0x00000000)
                        setStroke(if (hasFocus) (2 * dp2).toInt() else 0,
                            if (hasFocus) 0xFFE53935.toInt() else 0)
                        cornerRadius = 6 * dp2
                    }
                    v.background = bg
                    if (!hasFocus) {
                        sidePanel.postDelayed({
                            if (!btnRowSettings.isFocused && !btnDisplayMode.isFocused && !btnIconSize.isFocused) {
                                collapsePanel()
                            }
                        }, 100)
                    }
                }
            }

            // D-pad right stays within panel; only the rightmost button (btnRowSettings) closes it
            btnDisplayMode.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                        && event.action == android.view.KeyEvent.ACTION_DOWN) {
                    btnIconSize.requestFocus(); true
                } else false
            }
            btnIconSize.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                        && event.action == android.view.KeyEvent.ACTION_DOWN) {
                    btnRowSettings.requestFocus(); true
                } else false
            }
            btnRowSettings.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                        && event.action == android.view.KeyEvent.ACTION_DOWN) {
                    collapsePanel(); true
                } else false
            }

            when (item) {
                is RowItem.CategoryRow -> bindCategory(item)
                is RowItem.ChannelRow  -> bindChannel(item)
            }
        }

        private fun expandPanel() {
            if (panelOpen) return
            panelOpen = true
            val dp = itemView.resources.displayMetrics.density
            sidePanel.visibility       = View.VISIBLE
            btnRowSettings.visibility  = View.VISIBLE
            btnIconSize.visibility     = View.VISIBLE
            btnRowSettings.isFocusable = true
            btnIconSize.isFocusable    = true
            // For category rows show display mode toggle; hide for channel rows
            if (!isChannel) {
                btnDisplayMode.visibility  = View.VISIBLE
                btnDisplayMode.isFocusable = true
            } else {
                btnDisplayMode.visibility  = View.GONE
                btnDisplayMode.isFocusable = false
            }
            sidePanel.clearAnimation()
            rowContent.clearAnimation()
            sidePanel.animate()
                .translationX(0f)
                .setDuration(150)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .withEndAction {
                    sidePanel.translationX = 0f
                    if (!isChannel) btnDisplayMode.post { btnDisplayMode.requestFocus() }
                    else btnIconSize.post { btnIconSize.requestFocus() }
                }
                .start()
            rowContent.animate()
                .translationX(136f * dp)
                .setDuration(150)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }

        private fun collapsePanel() {
            if (!panelOpen) return
            panelOpen = false
            val dp = itemView.resources.displayMetrics.density
            btnRowSettings.isFocusable = false
            btnDisplayMode.isFocusable = false
            btnIconSize.isFocusable    = false
            sidePanel.clearAnimation()
            rowContent.clearAnimation()
            sidePanel.animate()
                .translationX(-136f * dp)
                .setDuration(150)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .withEndAction {
                    sidePanel.visibility      = View.INVISIBLE
                    btnRowSettings.visibility = View.GONE
                    btnDisplayMode.visibility = View.GONE
                    btnIconSize.visibility    = View.GONE
                }
                .start()
            rowContent.animate()
                .translationX(0f)
                .setDuration(150)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }

        private fun addItemSpacingDecoration() {
            rvApps.runCatching { removeItemDecorationAt(0) }
            if (itemSpacingDp > 0) {
                rvApps.addItemDecoration(object : RecyclerView.ItemDecoration() {
                    override fun getItemOffsets(
                        outRect: android.graphics.Rect, view: View,
                        parent: RecyclerView, state: RecyclerView.State
                    ) {
                        outRect.right = (itemSpacingDp * view.resources.displayMetrics.density).toInt()
                    }
                })
            }
        }

        private fun bindCategory(item: RowItem.CategoryRow) {
            if (item.apps.isEmpty()) {
                rvApps.visibility     = View.GONE
                emptyState.visibility = View.VISIBLE
                tvEmptyMsg.text       = "No apps in this category"
                return
            }
            rvApps.visibility     = View.VISIBLE
            emptyState.visibility = View.GONE
            val effectiveIconSize = item.row.iconSizeLabelOverride
                ?.let { com.shrine.launcher.data.model.iconSizeDp(it) }
                ?: iconSizeDp
            val adapter = AppsAdapter(
                displayMode         = item.row.cardDisplayMode,
                cornerRadiusPercent = cornerRadiusPercent,
                iconSizeDp          = effectiveIconSize,
                onAppClick          = onAppClick,
                onAppLongClick      = onAppLongClick,
                onFocused           = { onRowFocused(item.row.title) },
                onLeftFromFirst     = { expandPanel() }
            )
            val paddingPx = (rowStartPaddingDp * rvApps.resources.displayMetrics.density).toInt()
            rvApps.setPadding(paddingPx, 0, 0, 0)
            rvApps.layoutManager =
                LinearLayoutManager(rvApps.context, LinearLayoutManager.HORIZONTAL, false)
            addItemSpacingDecoration()
            rvApps.adapter = adapter
            rvApps.setHasFixedSize(true)
            adapter.submitList(item.apps)
        }

        private fun bindChannel(item: RowItem.ChannelRow) {
            if (item.content.isEmpty()) {
                rvApps.visibility     = View.GONE
                emptyState.visibility = View.VISIBLE
                tvEmptyMsg.text       = when (item.row.channelType) {
                    com.shrine.launcher.data.model.ChannelType.CONTINUE_WATCHING ->
                        "Nothing in progress — start watching something"
                    com.shrine.launcher.data.model.ChannelType.WATCH_NEXT ->
                        "No upcoming episodes"
                    else -> "No content available"
                }
                itemView.visibility = View.VISIBLE
                return
            }
            rvApps.visibility     = View.VISIBLE
            emptyState.visibility = View.GONE
            itemView.visibility   = View.VISIBLE
            val effectiveIconSize = item.row.iconSizeLabelOverride
                ?.let { com.shrine.launcher.data.model.iconSizeDp(it) }
                ?: iconSizeDp
            val adapter = ChannelAdapter(
                displayMode         = item.row.cardDisplayMode,
                cornerRadiusPercent = cornerRadiusPercent,
                iconSizeDp          = effectiveIconSize,
                onClick             = onContentClick,
                onLongClick         = onContentLongClick,
                onFocused           = { onRowFocused(item.row.title) },
                onLeftFromFirst     = { expandPanel() }
            )
            val paddingPx2 = (rowStartPaddingDp * rvApps.resources.displayMetrics.density).toInt()
            rvApps.setPadding(paddingPx2, 0, 0, 0)
            rvApps.layoutManager =
                LinearLayoutManager(rvApps.context, LinearLayoutManager.HORIZONTAL, false)
            addItemSpacingDecoration()
            rvApps.adapter = adapter
            rvApps.setHasFixedSize(false)
            adapter.submitList(item.content)
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<RowItem>() {
            override fun areItemsTheSame(a: RowItem, b: RowItem): Boolean {
                val aId = when (a) { is RowItem.CategoryRow -> a.row.id; is RowItem.ChannelRow -> a.row.id }
                val bId = when (b) { is RowItem.CategoryRow -> b.row.id; is RowItem.ChannelRow -> b.row.id }
                return aId == bId
            }
            override fun areContentsTheSame(a: RowItem, b: RowItem) = a == b
        }
    }
}
