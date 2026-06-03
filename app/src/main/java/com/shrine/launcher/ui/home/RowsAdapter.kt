package com.shrine.launcher.ui.home

import android.graphics.drawable.GradientDrawable
import android.view.KeyEvent
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

val RowItem.launcherRow: LauncherRow get() = when (this) {
    is RowItem.CategoryRow -> row
    is RowItem.ChannelRow  -> row
}

class RowsAdapter(
    private val onAppClick: (AppInfo) -> Unit,
    private val onAppLongClick: (AppInfo) -> Unit,
    private val onContentClick: (TvContent) -> Unit,
    private val onContentLongClick: (TvContent) -> Unit,
    private val onRowFocused: (String) -> Unit,
    private val onRowSettingsClick: (LauncherRow) -> Unit,
    private val onRowDisplayModeToggle: (LauncherRow) -> Unit,
    private val onRowMoveUp: (LauncherRow) -> Unit = {},
    private val onRowMoveDown: (LauncherRow) -> Unit = {},
    private val onPanelsCollapsed: (() -> Unit)? = null,
    private val cornerRadiusPercent: Int = 50,
    private val iconSizeDp: Int = 88,
    private val rowStartPaddingDp: Int = 24,
    private val itemSpacingDp: Int = 50,
    private val rowSpacingDp: Int = 24
) : ListAdapter<RowItem, RowsAdapter.RowViewHolder>(DIFF) {

    // ── Global panel state ────────────────────────────────────────────────────
    var allPanelsOpen = false
        private set

    // After a row-move, focus this row's btnMoveRow and re-enter move-mode
    var pendingMoveFocusRowId: String? = null

    private var attachedRecyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(rv: RecyclerView) {
        super.onAttachedToRecyclerView(rv)
        attachedRecyclerView = rv
    }

    override fun onDetachedFromRecyclerView(rv: RecyclerView) {
        super.onDetachedFromRecyclerView(rv)
        attachedRecyclerView = null
    }

    /** Expand all currently-visible row panels. Called when any single panel opens. */
    private fun expandAllVisiblePanels() {
        val rv = attachedRecyclerView ?: return
        for (i in 0 until rv.childCount) {
            val vh = rv.getChildViewHolder(rv.getChildAt(i)) as? RowViewHolder
            vh?.expandPanel(animate = true, grantFocus = false)
        }
    }

    /** Collapse all currently-visible row panels and notify HomeActivity for focus restore. */
    fun collapseAllPanels() {
        if (!allPanelsOpen) return
        allPanelsOpen = false
        val rv = attachedRecyclerView ?: return
        for (i in 0 until rv.childCount) {
            val vh = rv.getChildViewHolder(rv.getChildAt(i)) as? RowViewHolder
            vh?.collapsePanel()
        }
        onPanelsCollapsed?.invoke()
    }

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is RowItem.CategoryRow -> 0
        is RowItem.ChannelRow  -> 1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false)
        return RowViewHolder(v)
    }

    override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item)
        if (allPanelsOpen) {
            holder.expandPanel(animate = false, grantFocus = false)
        }
        val rowId = item.launcherRow.id
        if (rowId == pendingMoveFocusRowId) {
            pendingMoveFocusRowId = null
            holder.itemView.post { holder.enterMoveMode() }
        }
    }

    inner class RowViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvLabel:        TextView     = view.findViewById(R.id.tvRowLabel)
        private val tvKind:         TextView     = view.findViewById(R.id.tvRowKind)
        private val rvApps:         RecyclerView = view.findViewById(R.id.rvApps)
        private val btnRowSettings: ImageButton  = view.findViewById(R.id.btnRowSettings)
        private val btnDisplayMode: ImageButton  = view.findViewById(R.id.btnToggleDisplayMode)
        private val btnMoveRow:     ImageButton  = view.findViewById(R.id.btnMoveRow)
        private val sidePanel:   View = view.findViewById(R.id.sidePanel)
        private val rowContent:  View = view.findViewById(R.id.rowContent)
        private val emptyState: View = view.findViewById(R.id.emptyState)
        private val tvEmptyMsg: android.widget.TextView = view.findViewById(R.id.tvEmptyMessage)

        private var panelOpen = false
        private var inMoveMode = false
        private var currentRow: LauncherRow? = null

        fun bind(item: RowItem) {
            val row = item.launcherRow
            currentRow = row
            val dp = itemView.resources.displayMetrics.density

            tvLabel.text = row.title

            // Reset panel state on rebind
            panelOpen = false
            inMoveMode = false
            sidePanel.translationX  = -136f * dp
            rowContent.translationX = 0f
            sidePanel.visibility    = View.INVISIBLE
            listOf(btnRowSettings, btnDisplayMode, btnMoveRow).forEach {
                it.visibility  = View.GONE
                it.isFocusable = false
            }

            val rowSpacingPx = (rowSpacingDp * dp).toInt()
            itemView.setPadding(0, 0, 0, rowSpacingPx)
            (itemView as? ViewGroup)?.clipToPadding = true

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

            btnDisplayMode.setOnClickListener { onRowDisplayModeToggle(row) }
            btnRowSettings.setOnClickListener { onRowSettingsClick(row) }
            btnMoveRow.setOnClickListener {
                inMoveMode = !inMoveMode
                updateMoveModeVisual(inMoveMode)
                btnMoveRow.requestFocus()
            }

            // Set unified key listeners for all three panel buttons
            setupPanelButtonKeys()

            when (item) {
                is RowItem.CategoryRow -> bindCategory(item)
                is RowItem.ChannelRow  -> bindChannel(item)
            }
        }

        private fun setupPanelButtonKeys() {
            listOf(
                btnDisplayMode to R.id.btnToggleDisplayMode,
                btnMoveRow     to R.id.btnMoveRow,
                btnRowSettings to R.id.btnRowSettings
            ).forEach { (btn, btnId) ->
                btn.setOnFocusChangeListener { v, hasFocus ->
                    if (btn !== btnMoveRow || !inMoveMode) {
                        applyButtonFocusRing(v, hasFocus)
                    }
                }

                btn.setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    val row = currentRow ?: return@setOnKeyListener false

                    // Move-mode intercepts UP/DOWN exclusively
                    if (btn === btnMoveRow && inMoveMode) {
                        return@setOnKeyListener when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP -> {
                                onRowMoveUp(row); true
                            }
                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                onRowMoveDown(row); true
                            }
                            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                                inMoveMode = false
                                updateMoveModeVisual(false)
                                false  // pass through so normal left/right nav still works
                            }
                            KeyEvent.KEYCODE_BACK -> {
                                inMoveMode = false
                                updateMoveModeVisual(false)
                                this@RowsAdapter.collapseAllPanels()
                                true
                            }
                            else -> false
                        }
                    }

                    // Normal panel-button key handling
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            navigateToPanelDirection(-1, btnId); true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            navigateToPanelDirection(+1, btnId); true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> when (btn) {
                            btnDisplayMode -> { btnMoveRow.requestFocus(); true }
                            btnMoveRow     -> { btnRowSettings.requestFocus(); true }
                            btnRowSettings -> { this@RowsAdapter.collapseAllPanels(); true }
                            else -> false
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> when (btn) {
                            btnRowSettings -> { btnMoveRow.requestFocus(); true }
                            btnMoveRow     -> { btnDisplayMode.requestFocus(); true }
                            btnDisplayMode -> { this@RowsAdapter.collapseAllPanels(); true }
                            else -> false
                        }
                        else -> false
                    }
                }
            }
        }

        /** Navigate UP (-1) or DOWN (+1) to the same button in an adjacent row's panel. */
        private fun navigateToPanelDirection(delta: Int, btnId: Int) {
            val pos = adapterPosition
            if (pos == RecyclerView.NO_POSITION) return
            val targetPos = pos + delta
            if (targetPos < 0 || targetPos >= itemCount) return
            val rv = attachedRecyclerView ?: return
            val lm = rv.layoutManager as? LinearLayoutManager ?: return
            val targetView = lm.findViewByPosition(targetPos) ?: return
            targetView.findViewById<View>(btnId)?.takeIf { it.isFocusable }?.requestFocus()
        }

        fun expandPanel(animate: Boolean = true, grantFocus: Boolean = true) {
            if (panelOpen) return
            panelOpen = true
            val dp = itemView.resources.displayMetrics.density

            sidePanel.visibility    = View.VISIBLE
            listOf(btnDisplayMode, btnMoveRow, btnRowSettings).forEach {
                it.visibility  = View.VISIBLE
                it.isFocusable = true
            }

            // Block cards from receiving focus while panel is open
            rvApps.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS

            sidePanel.clearAnimation()
            rowContent.clearAnimation()

            if (animate) {
                sidePanel.animate()
                    .translationX(0f)
                    .setDuration(150)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .withEndAction {
                        sidePanel.translationX = 0f
                        if (grantFocus) btnRowSettings.post { btnRowSettings.requestFocus() }
                    }
                    .start()
                rowContent.animate()
                    .translationX(136f * dp)
                    .setDuration(150)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            } else {
                sidePanel.translationX  = 0f
                rowContent.translationX = 136f * dp
                if (grantFocus) btnRowSettings.post { btnRowSettings.requestFocus() }
            }
        }

        fun collapsePanel() {
            if (!panelOpen) return
            panelOpen = false
            inMoveMode = false
            val dp = itemView.resources.displayMetrics.density

            listOf(btnRowSettings, btnDisplayMode, btnMoveRow).forEach {
                it.isFocusable = false
            }
            rvApps.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            updateMoveModeVisual(false)

            sidePanel.clearAnimation()
            rowContent.clearAnimation()
            sidePanel.animate()
                .translationX(-136f * dp)
                .setDuration(150)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .withEndAction {
                    sidePanel.visibility    = View.INVISIBLE
                    listOf(btnRowSettings, btnDisplayMode, btnMoveRow).forEach {
                        it.visibility = View.GONE
                    }
                }
                .start()
            rowContent.animate()
                .translationX(0f)
                .setDuration(150)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }

        /** Enter move-mode and focus btnMoveRow (used after a row swap to restore state). */
        fun enterMoveMode() {
            inMoveMode = true
            updateMoveModeVisual(true)
            btnMoveRow.requestFocus()
        }

        private fun updateMoveModeVisual(active: Boolean) {
            val dp = itemView.resources.displayMetrics.density
            btnMoveRow.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(if (active) 0x33E53935.toInt() else 0x00000000)
                setStroke(
                    if (active) (2 * dp).toInt() else 0,
                    if (active) 0xFFE53935.toInt() else 0
                )
                cornerRadius = 6 * dp
            }
        }

        private fun applyButtonFocusRing(v: View, hasFocus: Boolean) {
            val dp = v.resources.displayMetrics.density
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0x00000000)
                setStroke(
                    if (hasFocus) (2 * dp).toInt() else 0,
                    if (hasFocus) 0xFFE53935.toInt() else 0
                )
                cornerRadius = 6 * dp
            }
        }

        private fun triggerPanelOpen() {
            // Reset every visible row to card 0 so focus is always clean after panel closes
            (itemView.parent as? RecyclerView)?.let { rv ->
                for (i in 0 until rv.childCount) {
                    rv.getChildAt(i)
                        ?.findViewById<RecyclerView>(R.id.rvApps)
                        ?.scrollToPosition(0)
                }
            }
            if (!allPanelsOpen) {
                allPanelsOpen = true
                // Expand this row with focus, all others without
                expandPanel(animate = true, grantFocus = true)
                expandAllVisiblePanels()
            } else {
                expandPanel(animate = true, grantFocus = true)
            }
        }

        private fun addItemSpacingDecoration() {
            rvApps.runCatching { removeItemDecorationAt(0) }
            rvApps.addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(
                    outRect: android.graphics.Rect, view: View,
                    parent: RecyclerView, state: RecyclerView.State
                ) {
                    val d = view.resources.displayMetrics.density
                    outRect.right = (itemSpacingDp * d).toInt()
                }
            })
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
                itemSpacingDp       = itemSpacingDp,
                onAppClick          = onAppClick,
                onAppLongClick      = onAppLongClick,
                onFocused           = { onRowFocused(item.row.title) },
                onLeftFromFirst     = { triggerPanelOpen() }
            )
            val density = rvApps.resources.displayMetrics.density
            val paddingPx = (rowStartPaddingDp * density).toInt()
            val peekPx = (effectiveIconSize * 0.3f * density).toInt()
            rvApps.setPadding(paddingPx, 0, peekPx, 0)
            rvApps.clipToPadding = false
            rvApps.clipChildren = false
            rvApps.layoutManager = FullPreloadLayoutManager(rvApps.context)
            addItemSpacingDecoration()
            rvApps.adapter = adapter
            rvApps.setHasFixedSize(false)
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
                itemSpacingDp       = itemSpacingDp,
                onClick             = onContentClick,
                onLongClick         = onContentLongClick,
                onFocused           = { onRowFocused(item.row.title) },
                onLeftFromFirst     = { triggerPanelOpen() }
            )
            val density = rvApps.resources.displayMetrics.density
            val paddingPx = (rowStartPaddingDp * density).toInt()
            val peekPx = (effectiveIconSize * 0.3f * density).toInt()
            rvApps.setPadding(paddingPx, 0, peekPx, 0)
            rvApps.clipToPadding = false
            rvApps.clipChildren = false
            rvApps.layoutManager = FullPreloadLayoutManager(rvApps.context)
            addItemSpacingDecoration()
            rvApps.adapter = adapter
            rvApps.setHasFixedSize(false)
            adapter.submitList(item.content)
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<RowItem>() {
            override fun areItemsTheSame(a: RowItem, b: RowItem) =
                a.launcherRow.id == b.launcherRow.id
            override fun areContentsTheSame(a: RowItem, b: RowItem) = a == b
        }
    }
}

private class FullPreloadLayoutManager(context: android.content.Context) :
    LinearLayoutManager(context, HORIZONTAL, false) {
    override fun calculateExtraLayoutSpace(state: RecyclerView.State, extraLayoutSpace: IntArray) {
        extraLayoutSpace[0] = 100_000
        extraLayoutSpace[1] = 100_000
    }
}
