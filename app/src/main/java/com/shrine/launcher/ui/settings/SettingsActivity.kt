package com.shrine.launcher.ui.settings

import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.shrine.launcher.R
import com.shrine.launcher.data.model.*
import com.shrine.launcher.data.repository.AppRepository
import com.shrine.launcher.data.repository.PreferencesRepository
import com.shrine.launcher.databinding.ActivitySettingsBinding
import com.shrine.launcher.ui.home.RowSettingsDialog
import com.shrine.launcher.util.ConfigManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.UUID

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var repo: PreferencesRepository
    private lateinit var rowsListAdapter: RowsListAdapter
    private var lastNavIndex = 0

    private var appearanceSnapshot: LauncherPrefs? = null
    private var appearanceDirty = false

    private val panels get() = listOf(
        binding.panelRows, binding.panelAppearance, binding.panelExport
    )
    private val navItems get() = listOf(
        binding.navRows, binding.navAppearance, binding.navExport, binding.navInstaller
    )

    private val wallpaperPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { saveWallpaper(it) } }

    private val slideshowPicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) saveSlideshow(uris)
    }

    private val configExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let {
            val ok = ConfigManager.exportToUri(this, it)
            Toast.makeText(this, if (ok) "Config exported" else "Export failed",
                Toast.LENGTH_SHORT).show()
        }
    }

    private val configImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val ok = ConfigManager.importFromUri(this, it)
            Toast.makeText(this,
                if (ok) "Config imported — restarting" else "Import failed",
                Toast.LENGTH_LONG).show()
            if (ok) recreate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = PreferencesRepository.getInstance(this)

        setupNavigation()
        setupRowsManager()
        setupAppearanceSettings()
        setupExportButtons()
        setupWallpaperPreviewAspectRatio()

        showPanel(lastNavIndex)
    }

    override fun onResume() {
        super.onResume()
        reloadRows()
        binding.root.post { navItems.getOrNull(lastNavIndex)?.requestFocus() }
    }

    override fun onBackPressed() {
        if (lastNavIndex == 1 && appearanceDirty) {
            showUnsavedChangesDialog()
        } else {
            super.onBackPressed()
        }
    }

    // ── Navigation ─────────────────────────────────────────────────────────────

    private fun setupNavigation() {
        navItems.forEachIndexed { index, tv ->
            tv.setOnClickListener {
                if (index == 3) {
                    startActivity(Intent(this,
                        com.shrine.launcher.ui.installer.AppInstallerActivity::class.java))
                } else {
                    lastNavIndex = index
                    navigateTo(index)
                }
            }
            tv.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    val dp = tv.resources.displayMetrics.density
                    val bg = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        setColor(0x00000000)
                        setStroke((2 * dp).toInt(), getColor(R.color.accent))
                        cornerRadius = 6 * dp
                    }
                    tv.background = bg
                } else {
                    tv.setBackgroundResource(android.R.color.transparent)
                }
                // Text color: accent only if this is the SELECTED item (lastNavIndex)
                tv.setTextColor(
                    if (index == lastNavIndex) getColor(R.color.accent)
                    else getColor(R.color.text_primary)
                )
            }
            // D-pad right from any nav item enters the panel for that nav item
            if (index < panels.size) {
                tv.setOnKeyListener { _, keyCode, event ->
                    if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                            && event.action == android.view.KeyEvent.ACTION_DOWN) {
                        lastNavIndex = index
                        commitNav(index)
                        true
                    } else false
                }
            }
        }
        setupFocusContainment()
    }

    private fun navigateTo(index: Int) {
        if (lastNavIndex == 1 && appearanceDirty && index != 1) {
            showUnsavedChangesDialog(onDiscard = { commitNav(index) })
        } else {
            commitNav(index)
        }
    }

    private fun commitNav(index: Int) {
        lastNavIndex = index
        showPanel(index)
        if (index == 1) {
            appearanceSnapshot = repo.loadPrefs()
            appearanceDirty = false
        }
        // Move focus into the first focusable item in the selected panel
        binding.rightPanel.post {
            binding.rightPanel.post {
                val panel = panels.getOrNull(index) ?: return@post
                val target = findFirstFocusable(panel)
                target?.requestFocus()
            }
        }
    }

    private fun findFirstFocusable(v: android.view.View): android.view.View? {
        if (v.isFocusable && v.visibility == android.view.View.VISIBLE) return v
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) {
                val result = findFirstFocusable(v.getChildAt(i))
                if (result != null) return result
            }
        }
        return null
    }

    private fun setupFocusContainment() {
        binding.rightPanel.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                    android.view.KeyEvent.KEYCODE_BACK -> {
                        if (lastNavIndex == 1 && appearanceDirty) {
                            showUnsavedChangesDialog()
                        } else {
                            navItems.getOrNull(lastNavIndex)?.requestFocus()
                        }
                        true
                    }
                    else -> false
                }
            } else false
        }
    }

    private fun showPanel(index: Int) {
        lastNavIndex = index
        panels.forEachIndexed { i, panel ->
            panel.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        navItems.forEachIndexed { i, tv ->
            tv.setBackgroundResource(android.R.color.transparent)
            tv.setTextColor(if (i == index) getColor(R.color.accent) else getColor(R.color.text_primary))
        }
    }

    private fun showUnsavedChangesDialog(onDiscard: (() -> Unit)? = null) {
        val prefs = repo.loadPrefs()
        val snap  = appearanceSnapshot ?: return

        val changes = mutableListOf<String>()
        if (prefs.iconSizeLabel != snap.iconSizeLabel)
            changes.add("Icon size: ${snap.iconSizeLabel} → ${prefs.iconSizeLabel}")
        if (prefs.cardCornerRadiusPercent != snap.cardCornerRadiusPercent)
            changes.add("Corner roundness: ${snap.cardCornerRadiusPercent}% → ${prefs.cardCornerRadiusPercent}%")
        if (prefs.rowsBottomMarginPercent != snap.rowsBottomMarginPercent)
            changes.add("Bottom margin: ${snap.rowsBottomMarginPercent}% → ${prefs.rowsBottomMarginPercent}%")
        if (prefs.rowStartPaddingPercent != snap.rowStartPaddingPercent)
            changes.add("Start margin: ${snap.rowStartPaddingPercent}% → ${prefs.rowStartPaddingPercent}%")
        if (prefs.wallpaperUri != snap.wallpaperUri)
            changes.add("Wallpaper changed")
        if (prefs.clockEnabled != snap.clockEnabled)
            changes.add("Clock: ${if (prefs.clockEnabled) "on" else "off"}")
        if (prefs.dateEnabled != snap.dateEnabled)
            changes.add("Date: ${if (prefs.dateEnabled) "on" else "off"}")
        if (prefs.clockFormat24h != snap.clockFormat24h)
            changes.add("Clock format changed")

        if (changes.isEmpty()) { onDiscard?.invoke() ?: finish(); return }

        val msg = "Unsaved changes:\n• " + changes.joinToString("\n• ")

        AlertDialog.Builder(this)
            .setTitle("Unsaved Changes")
            .setMessage(msg)
            .setPositiveButton("Save") { _, _ ->
                saveAppearance()
                appearanceDirty = false
                onDiscard?.invoke() ?: finish()
            }
            .setNeutralButton("Undo") { _, _ ->
                appearanceSnapshot?.let { repo.savePrefs(it) }
                appearanceDirty = false
                onDiscard?.invoke() ?: finish()
            }
            .setNegativeButton("Stay", null)
            .show()
    }

    // ── Categories ─────────────────────────────────────────────────────────────

    private fun setupRowsManager() {
        rowsListAdapter = RowsListAdapter(
            onEdit = { row -> showRowOptionsDialog(row) },
            onDelete = { row -> repo.removeRow(row.id); reloadRows() },
            onVisibilityToggle = { row ->
                repo.updateRow(row.copy(isVisible = !row.isVisible)); reloadRows()
            },
            onDisplayModeToggle = { row ->
                val newMode = if (row.cardDisplayMode == CardDisplayMode.ICON)
                    CardDisplayMode.BANNER else CardDisplayMode.ICON
                repo.updateRow(row.copy(cardDisplayMode = newMode)); reloadRows()
            }
        )
        binding.rvRows.apply {
            layoutManager = LinearLayoutManager(this@SettingsActivity)
            adapter = rowsListAdapter
        }
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder,
                                target: RecyclerView.ViewHolder): Boolean {
                val rows = rowsListAdapter.currentRows.toMutableList()
                Collections.swap(rows, vh.adapterPosition, target.adapterPosition)
                repo.saveRows(rows); rowsListAdapter.setRows(rows)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}
        })
        touchHelper.attachToRecyclerView(binding.rvRows)
        binding.btnAddRow.setOnClickListener { showAddRowDialog() }
        reloadRows()
    }

    private fun reloadRows() {
        if (::rowsListAdapter.isInitialized) rowsListAdapter.setRows(repo.loadRows())
    }

    private fun showRowOptionsDialog(row: LauncherRow) {
        val modeLabel = if (row.cardDisplayMode == CardDisplayMode.ICON)
            "Switch to Banner View" else "Switch to Icon View"
        val pinLabel  = if (row.isPinnedToTop) "Unpin from Top" else "Pin to Top"
        val items = mutableListOf("Rename", modeLabel, pinLabel)
        items.add("Icon Size")
        if (row.kind == RowKind.CATEGORY) {
            items.add("Edit Apps")
            items.add("Reorder Apps")
        }
        if (row.kind == RowKind.CHANNEL) {
            items.add("Filter Sources")
            items.add("Auto-refresh")
        }

        AlertDialog.Builder(this).setTitle(row.title)
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    "Rename"         -> showRenameDialog(row)
                    modeLabel        -> {
                        val newMode = if (row.cardDisplayMode == CardDisplayMode.ICON)
                            CardDisplayMode.BANNER else CardDisplayMode.ICON
                        repo.updateRow(row.copy(cardDisplayMode = newMode)); reloadRows()
                    }
                    pinLabel         -> {
                        repo.updateRow(row.copy(isPinnedToTop = !row.isPinnedToTop))
                        reloadRows()
                    }
                    "Edit Apps"      -> startActivity(
                        Intent(this, RowEditorActivity::class.java)
                            .putExtra(RowEditorActivity.EXTRA_ROW_ID, row.id))
                    "Reorder Apps"   -> showReorderDialog(row)
                    "Filter Sources" -> showChannelSourcesDialog(row)
                    "Icon Size"      -> showIconSizeDialog(row)
                    "Auto-refresh"   -> showAutoRefreshDialog(row)
                }
            }.show()
    }

    private fun showIconSizeDialog(row: LauncherRow) {
        val sizes   = arrayOf("Global (default)", "S — Small", "M — Medium", "L — Large", "XL — Extra Large")
        val labels  = arrayOf<String?>(null, "S", "M", "L", "XL")
        val current = labels.indexOfFirst { it == row.iconSizeLabelOverride }.coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Icon Size — ${row.title}")
            .setSingleChoiceItems(sizes, current) { dialog, which ->
                repo.updateRow(row.copy(iconSizeLabelOverride = labels[which]))
                reloadRows()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showAutoRefreshDialog(row: LauncherRow) {
        val options = arrayOf("Off (manual only)", "Every 15 minutes",
            "Every 30 minutes", "Every hour", "Every 2 hours")
        val minutes = arrayOf(0, 15, 30, 60, 120)
        val current = minutes.indexOfFirst { it == row.autoRefreshMinutes }.coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Auto-refresh — ${row.title}")
            .setSingleChoiceItems(options, current) { dialog, which ->
                repo.updateRow(row.copy(autoRefreshMinutes = minutes[which]))
                reloadRows()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showChannelSourcesDialog(row: LauncherRow) {
        CoroutineScope(Dispatchers.Main).launch {
            val tvRepo = com.shrine.launcher.data.repository.TvContentRepository.getInstance(this@SettingsActivity)
            val allContent = withContext(Dispatchers.IO) {
                when (row.channelType) {
                    ChannelType.CONTINUE_WATCHING ->
                        tvRepo.getWatchNextPrograms(ChannelType.CONTINUE_WATCHING)
                    ChannelType.WATCH_NEXT ->
                        tvRepo.getWatchNextPrograms(ChannelType.WATCH_NEXT)
                    else -> tvRepo.getPreviewPrograms()
                }
            }
            val packages = allContent.map { it.packageName }
                .filter { it.isNotBlank() }.distinct().sorted()
            if (packages.isEmpty()) {
                Toast.makeText(this@SettingsActivity,
                    "No sources found for this channel", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val pm = packageManager
            val labels = packages.map { pkg ->
                try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
                catch (e: Exception) { pkg }
            }.toTypedArray()
            val checked = BooleanArray(packages.size) { i ->
                row.allowedPackages.isEmpty() || packages[i] in row.allowedPackages
            }
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle("Sources for ${row.title}")
                .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setPositiveButton("Save") { _, _ ->
                    val selected = packages.filterIndexed { i, _ -> checked[i] }
                    repo.updateRow(row.copy(
                        allowedPackages = if (selected.size == packages.size) emptyList() else selected
                    ))
                    reloadRows()
                }
                .setNegativeButton("Cancel", null).show()
        }
    }

    private fun showReorderDialog(row: LauncherRow) {
        val appRepo = AppRepository.getInstance(this)
        CoroutineScope(Dispatchers.Main).launch {
            val allApps = withContext(Dispatchers.IO) { appRepo.getAllApps() }
            RowSettingsDialog(this@SettingsActivity, row, allApps) { reloadRows() }.show()
        }
    }

    private fun showRenameDialog(row: LauncherRow) {
        val input = android.widget.EditText(this).apply { setText(row.title) }
        AlertDialog.Builder(this).setTitle("Rename Row").setView(input)
            .setPositiveButton("Save") { _, _ ->
                repo.updateRow(row.copy(title = input.text.toString().ifBlank { row.title }))
                reloadRows()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showAddRowDialog() {
        val options = arrayOf(
            "── CATEGORIES ──", "All Apps", "Custom App List",
            "── CHANNELS ──", "Continue Watching", "Watch Next", "New For You"
        )
        AlertDialog.Builder(this).setTitle("Add Row")
            .setItems(options) { _, which ->
                val row: LauncherRow? = when (which) {
                    1 -> LauncherRow(UUID.randomUUID().toString(), "All Apps",
                        RowKind.CATEGORY, categoryType = CategoryType.ALL_APPS)
                    2 -> LauncherRow(UUID.randomUUID().toString(), "Custom",
                        RowKind.CATEGORY, categoryType = CategoryType.CUSTOM)
                    4 -> LauncherRow(UUID.randomUUID().toString(), "Continue Watching",
                        RowKind.CHANNEL, channelType = ChannelType.CONTINUE_WATCHING)
                    5 -> LauncherRow(UUID.randomUUID().toString(), "Watch Next",
                        RowKind.CHANNEL, channelType = ChannelType.WATCH_NEXT)
                    6 -> LauncherRow(UUID.randomUUID().toString(), "New For You",
                        RowKind.CHANNEL, channelType = ChannelType.NEW_FOR_YOU)
                    else -> null
                }
                row?.let { repo.addRow(it); reloadRows() }
            }.show()
    }

    // ── Appearance ─────────────────────────────────────────────────────────────

    private var selectedSize = "M"

    private fun setupAppearanceSettings() {
        val prefs = repo.loadPrefs()
        selectedSize = prefs.iconSizeLabel

        val sizeButtons = listOf(
            binding.btnSizeS to "S", binding.btnSizeM to "M",
            binding.btnSizeL to "L", binding.btnSizeXL to "XL"
        )

        fun updateSizeSelection(sel: String) {
            sizeButtons.forEach { (btn, label) ->
                btn.setBackgroundResource(
                    if (label == sel) R.drawable.bg_size_btn_selected
                    else R.drawable.selector_size_btn
                )
                btn.setTextColor(if (label == sel) getColor(R.color.accent)
                    else getColor(R.color.text_primary))
            }
        }
        updateSizeSelection(selectedSize)
        sizeButtons.forEach { (btn, label) ->
            btn.setOnClickListener {
                selectedSize = label
                updateSizeSelection(label)
                appearanceDirty = true
            }
        }

        binding.sbCornerRadius.progress = prefs.cardCornerRadiusPercent / 10
        binding.tvCornerRadiusValue.text = "${prefs.cardCornerRadiusPercent}%"
        binding.sbCornerRadius.setOnSeekBarChangeListener(seekListener {
            binding.tvCornerRadiusValue.text = "${it * 10}%"
            appearanceDirty = true
        })

        binding.sbBottomMargin.progress = prefs.rowsBottomMarginPercent
        binding.tvBottomMarginValue.text = "${prefs.rowsBottomMarginPercent}%"
        binding.sbBottomMargin.setOnSeekBarChangeListener(seekListener {
            binding.tvBottomMarginValue.text = "$it%"
            appearanceDirty = true
        })

        binding.sbRowStartPadding.progress = prefs.rowStartPaddingPercent
        binding.tvRowStartPaddingValue.text = "${prefs.rowStartPaddingPercent}%"
        binding.sbRowStartPadding.setOnSeekBarChangeListener(seekListener {
            binding.tvRowStartPaddingValue.text = "$it%"
            appearanceDirty = true
        })

        binding.sbRowSpacing.progress = prefs.rowSpacingPercent
        binding.tvRowSpacingValue.text = "${prefs.rowSpacingPercent}%"
        binding.sbRowSpacing.setOnSeekBarChangeListener(seekListener {
            binding.tvRowSpacingValue.text = "$it%"
            appearanceDirty = true
        })

        binding.sbItemSpacing.progress = prefs.itemSpacingPercent
        binding.tvItemSpacingValue.text = "${prefs.itemSpacingPercent}%"
        binding.sbItemSpacing.setOnSeekBarChangeListener(seekListener {
            binding.tvItemSpacingValue.text = "$it%"
            appearanceDirty = true
        })

        binding.switchClock.isChecked = prefs.clockEnabled
        binding.switchDate.isChecked  = prefs.dateEnabled
        binding.switch24h.isChecked   = prefs.clockFormat24h
        binding.switchClock.setOnCheckedChangeListener { _, _ -> appearanceDirty = true }
        binding.switchDate.setOnCheckedChangeListener  { _, _ -> appearanceDirty = true }
        binding.switch24h.setOnCheckedChangeListener   { _, _ -> appearanceDirty = true }

        applyFocusOutline(binding.btnSaveAppearance)
        binding.btnSaveAppearance.setOnClickListener {
            saveAppearance()
            appearanceDirty = false
            Toast.makeText(this, "Configuration saved", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveAppearance() {
        val startMarginDp = (binding.sbRowStartPadding.progress * 1.2f).toInt()
        val intervalSecs  = slideshowIntervalValues.getOrElse(
            binding.sbSlideshowInterval.progress) { 300 }
        val current = repo.loadPrefs()
        repo.savePrefs(current.copy(
            iconSizeLabel             = selectedSize,
            cardCornerRadiusPercent   = binding.sbCornerRadius.progress * 10,
            rowsBottomMarginPercent   = binding.sbBottomMargin.progress,
            rowStartPaddingPercent    = binding.sbRowStartPadding.progress,
            rowStartPaddingDp         = startMarginDp,
            rowSpacingPercent         = binding.sbRowSpacing.progress,
            itemSpacingPercent        = binding.sbItemSpacing.progress,
            clockEnabled              = binding.switchClock.isChecked,
            dateEnabled               = binding.switchDate.isChecked,
            clockFormat24h            = binding.switch24h.isChecked,
            wallpaperIntervalSeconds  = intervalSecs,
            // Explicitly carry forward wallpaper fields so they're never lost
            wallpaperUri              = current.wallpaperUri,
            wallpaperUris             = current.wallpaperUris
        ))
        val saved = repo.loadPrefs()
        updateWallpaperPreview(saved.wallpaperUri ?: saved.wallpaperUris.firstOrNull())
    }

    // ── Wallpaper ──────────────────────────────────────────────────────────────

    private val slideshowIntervalValues = listOf(30, 60, 90, 120, 150, 180, 210, 240, 270, 300, 330, 360, 390, 420, 450, 480, 510, 540, 570, 600)
    private val slideshowIntervalLabels = listOf("30s","1m","1m30s","2m","2m30s","3m","3m30s","4m","4m30s","5m","5m30s","6m","6m30s","7m","7m30s","8m","8m30s","9m","9m30s","10m")

    private fun saveSlideshow(uris: List<Uri>) {
        val persisted = uris.mapNotNull { uri ->
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                uri.toString()
            } catch (e: Exception) { uri.toString() }
        }
        repo.savePrefs(repo.loadPrefs().copy(
            wallpaperUris = persisted,
            wallpaperUri  = null
        ))
        updateSlideshowCount(persisted.size)
        updateWallpaperPreview(persisted.firstOrNull())
        appearanceDirty = true
        Toast.makeText(this, "${persisted.size} images added to slideshow", Toast.LENGTH_SHORT).show()
    }

    private fun updateSlideshowCount(count: Int) {
        binding.tvSlideshowCount.text = when (count) {
            0    -> "No slideshow configured"
            1    -> "1 image selected (use Choose Image for single wallpaper)"
            else -> "$count images in slideshow"
        }
    }

    private fun setupWallpaperPreviewAspectRatio() {
        // One-shot layout listener: fires once after the frame's width is known,
        // sets height = width × 9/16, then removes itself to avoid re-triggering.
        binding.wallpaperFrame.viewTreeObserver.addOnGlobalLayoutListener(
            object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    val w = binding.wallpaperFrame.width
                    if (w > 0) {
                        binding.wallpaperFrame.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        val target = (w * 9f / 16f).toInt()
                        if (binding.wallpaperFrame.layoutParams.height != target) {
                            binding.wallpaperFrame.layoutParams.height = target
                            binding.wallpaperFrame.requestLayout()
                        }
                    }
                }
            }
        )
        repo.loadPrefs().let { p ->
            updateWallpaperPreview(p.wallpaperUri ?: p.wallpaperUris.firstOrNull())
        }
        applyFocusOutline(binding.btnPickWallpaper)
        applyFocusOutline(binding.btnClearWallpaper)
        binding.btnPickWallpaper.setOnClickListener {
            wallpaperPicker.launch(arrayOf("image/*"))
            appearanceDirty = true
        }
        binding.btnClearWallpaper.setOnClickListener {
            repo.savePrefs(repo.loadPrefs().copy(wallpaperUri = null))
            updateWallpaperPreview(null)
            appearanceDirty = true
        }

        val prefs = repo.loadPrefs()
        updateSlideshowCount(prefs.wallpaperUris.size)

        val intervalIdx = slideshowIntervalValues.indexOfFirst {
            it == prefs.wallpaperIntervalSeconds }.coerceAtLeast(0)
        binding.sbSlideshowInterval.progress = intervalIdx
        binding.tvSlideshowIntervalValue.text = slideshowIntervalLabels[intervalIdx]
        binding.sbSlideshowInterval.setOnSeekBarChangeListener(seekListener { p ->
            binding.tvSlideshowIntervalValue.text = slideshowIntervalLabels[p]
            appearanceDirty = true
        })

        applyFocusOutline(binding.btnPickSlideshow)
        applyFocusOutline(binding.btnClearSlideshow)

        binding.btnPickSlideshow.setOnClickListener {
            slideshowPicker.launch(arrayOf("image/*"))
        }
        binding.btnClearSlideshow.setOnClickListener {
            repo.savePrefs(repo.loadPrefs().copy(wallpaperUris = emptyList()))
            updateSlideshowCount(0)
            appearanceDirty = true
            Toast.makeText(this, "Slideshow cleared", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveWallpaper(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) { }
        repo.savePrefs(repo.loadPrefs().copy(wallpaperUri = uri.toString()))
        updateWallpaperPreview(uri.toString())
    }

    private fun updateWallpaperPreview(uriString: String?) {
        if (uriString.isNullOrBlank()) {
            binding.ivWallpaperPreview.setImageDrawable(null)
            binding.tvWallpaperNone.visibility = View.VISIBLE
        } else {
            binding.tvWallpaperNone.visibility = View.GONE
            Glide.with(this)
                .load(Uri.parse(uriString))
                .override(480, 270)   // load preview size only — avoids decoding full 4K image
                .centerCrop()
                .into(binding.ivWallpaperPreview)
        }
    }

    // ── Export ─────────────────────────────────────────────────────────────────

    private fun setupExportButtons() {
        applyFocusOutline(binding.btnExportConfig)
        applyFocusOutline(binding.btnImportConfig)
        applyFocusOutline(binding.btnCloudExport)
        applyFocusOutline(binding.btnCloudImport)

        binding.btnExportConfig.setOnClickListener {
            configExportLauncher.launch("shrine_launcher_config.json")
        }
        binding.btnImportConfig.setOnClickListener {
            configImportLauncher.launch(arrayOf("application/json", "*/*"))
        }

        binding.btnCloudExport.setOnClickListener {
            val json = ConfigManager.exportToString(this)
            if (json != null) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Shrine Launcher Config")
                    putExtra(Intent.EXTRA_TEXT, json as String)
                }
                startActivity(Intent.createChooser(intent, "Share Config"))
            } else {
                Toast.makeText(this, "Export failed", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnCloudImport.setOnClickListener {
            val clipboard = getSystemService(android.content.ClipboardManager::class.java)
            val text = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()
            if (!text.isNullOrBlank()) {
                val ok = ConfigManager.importFromString(this, text)
                Toast.makeText(this,
                    if (ok) "Config imported from clipboard — restarting"
                    else "Clipboard doesn't contain a valid config",
                    Toast.LENGTH_LONG).show()
                if (ok) recreate()
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun applyFocusOutline(v: View) {
        fun applyBg(fv: View, hasFocus: Boolean) {
            val dp = fv.resources.displayMetrics.density
            fv.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0x00000000)
                setStroke((2 * dp).toInt(),
                    if (hasFocus) 0xFFE53935.toInt() else 0xFFFFFFFF.toInt())
                cornerRadius = 8 * dp
            }
        }
        applyBg(v, false)
        v.setOnFocusChangeListener { fv, hasFocus -> applyBg(fv, hasFocus) }
    }

    private fun seekListener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }
}
