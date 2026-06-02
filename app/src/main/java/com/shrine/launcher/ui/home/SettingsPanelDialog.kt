package com.shrine.launcher.ui.home

import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.shrine.launcher.R
import com.shrine.launcher.data.model.AppInfo
import com.shrine.launcher.data.model.CardDisplayMode
import com.shrine.launcher.data.model.CategoryType
import com.shrine.launcher.data.model.ChannelType
import com.shrine.launcher.data.model.LauncherRow
import com.shrine.launcher.data.model.RowKind
import com.shrine.launcher.data.repository.AppRepository
import com.shrine.launcher.data.repository.PreferencesRepository
import com.shrine.launcher.data.repository.TvContentRepository
import com.shrine.launcher.ui.installer.AppInstallerActivity
import com.shrine.launcher.ui.settings.ManageSettingsActivity
import com.shrine.launcher.ui.settings.WallpaperAppearanceActivity
import com.shrine.launcher.util.ConfigManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

class SettingsPanelDialog(
    context: Context,
    private val wallpaperUri: String? = null,
    private val onDismissed: (() -> Unit)? = null,
    private val initialApp: AppInfo? = null          // if set, open directly to context menu
) : Dialog(context) {

    // ── Repos ──────────────────────────────────────────────────────────────────
    private val prefRepo by lazy { PreferencesRepository.getInstance(context) }
    private val appRepo  by lazy { AppRepository.getInstance(context) }
    private val tvRepo   by lazy { TvContentRepository.getInstance(context) }
    private val scope    = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pageJob: Job? = null

    // ── Navigation ─────────────────────────────────────────────────────────────
    private data class NavEntry(val title: String?, val builder: () -> Unit)
    private val navStack = ArrayDeque<NavEntry>()
    private var currentTitle: String? = null
    private var currentBuilder: () -> Unit = {}

    // ── Views ──────────────────────────────────────────────────────────────────
    private lateinit var header: LinearLayout
    private lateinit var ivLogo: ImageView
    private lateinit var tvPageTitle: TextView
    private lateinit var body: LinearLayout

    // ── dp helper ──────────────────────────────────────────────────────────────
    private val dp get() = context.resources.displayMetrics.density

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_settings_panel)
        configureWindow()

        header      = findViewById(R.id.panelHeader)
        ivLogo      = findViewById(R.id.ivPanelLogo)
        tvPageTitle = findViewById(R.id.tvPanelDate)   // reuse id; now a red title
        body        = findViewById(R.id.panelBody)

        tvPageTitle.setTextColor(0xFFE53935.toInt())
        tvPageTitle.textSize = 13f
        tvPageTitle.typeface = Typeface.DEFAULT_BOLD

        setOnDismissListener { onDismissed?.invoke() }

        if (initialApp != null) {
            // Open directly to context menu (from home screen long-press)
            showPage(initialApp.label, false) { buildContextMenu(initialApp) }
        } else {
            showPage(null, false) { buildMainMenu() }
        }
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

    override fun onBackPressed() {
        if (navStack.isEmpty()) { dismiss(); return }
        val prev = navStack.removeLast()
        currentTitle   = prev.title
        currentBuilder = prev.builder
        rawShowPage(prev.title)
        prev.builder()
    }

    override fun onStop() { super.onStop(); scope.cancel() }

    // ── Navigation core ────────────────────────────────────────────────────────

    /** Push the current page to the back-stack and show a new page. */
    fun navigateTo(title: String, builder: () -> Unit) {
        navStack.addLast(NavEntry(currentTitle, currentBuilder))
        currentTitle   = title
        currentBuilder = builder
        rawShowPage(title)
        builder()
    }

    private fun showPage(title: String?, push: Boolean = true, builder: () -> Unit) {
        if (push) {
            navStack.addLast(NavEntry(currentTitle, currentBuilder))
        }
        currentTitle   = title
        currentBuilder = builder
        rawShowPage(title)
        builder()
    }

    private fun rawShowPage(title: String?) {
        pageJob?.cancel()
        pageJob = null
        body.removeAllViews()
        if (title == null) {
            ivLogo.visibility      = View.VISIBLE
            tvPageTitle.visibility = View.GONE
        } else {
            ivLogo.visibility      = View.GONE
            tvPageTitle.visibility = View.VISIBLE
            tvPageTitle.text       = title
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UI HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    fun addSectionHeader(text: String) {
        val tv = TextView(context)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, (12 * dp).toInt(), 0, (2 * dp).toInt())
        tv.layoutParams = lp
        tv.text = text
        tv.setTextColor(0xFFE53935.toInt())
        tv.textSize = 10f
        tv.letterSpacing = 0.12f
        tv.typeface = Typeface.DEFAULT_BOLD
        tv.setPadding((20 * dp).toInt(), 0, (20 * dp).toInt(), 0)
        body.addView(tv)
    }

    fun addSeparator() {
        val v = View(context)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt())
        lp.setMargins(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
        v.layoutParams = lp
        v.setBackgroundColor(0x33FFFFFF)
        body.addView(v)
    }

    /**
     * Standard menu entry: icon (tinted red) + label + optional arrow.
     * Returns the inflated view so callers can further customise it.
     */
    fun addEntry(
        label: String,
        iconRes: Int = 0,
        showArrow: Boolean = false,
        labelColor: Int = 0xFFB0B0B0.toInt(),
        onClick: (() -> Unit)? = null
    ): View {
        val v       = LayoutInflater.from(context).inflate(R.layout.item_panel_menu_entry, body, false)
        val tvLabel = v.findViewById<TextView>(R.id.tvEntryLabel)
        val ivIcon  = v.findViewById<ImageView>(R.id.ivEntryIcon)
        val ivArrow = v.findViewById<ImageView>(R.id.ivEntryArrow)

        tvLabel.text = label
        tvLabel.setTextColor(labelColor)

        if (iconRes != 0) {
            try {
                ivIcon.setImageResource(iconRes)
                ivIcon.setColorFilter(0xFFE53935.toInt())   // #15 — red icon tint
                ivIcon.visibility = View.VISIBLE
            } catch (e: Exception) { ivIcon.visibility = View.GONE }
        } else {
            ivIcon.visibility = View.GONE
        }
        ivArrow.visibility = if (showArrow) View.VISIBLE else View.GONE

        applyFocus(v, tvLabel)
        onClick?.let { v.setOnClickListener { it() } }
        body.addView(v)
        return v
    }

    /** Toggle row: label on left, ON/OFF pill on right. */
    fun addToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val v       = LayoutInflater.from(context).inflate(R.layout.item_panel_menu_entry, body, false)
        val tvLabel = v.findViewById<TextView>(R.id.tvEntryLabel)
        val ivIcon  = v.findViewById<ImageView>(R.id.ivEntryIcon)
        val ivArrow = v.findViewById<ImageView>(R.id.ivEntryArrow)
        tvLabel.text = label
        ivIcon.visibility  = View.GONE
        ivArrow.visibility = View.GONE

        var state = checked
        val pill  = TextView(context)
        pill.text = if (state) "ON" else "OFF"
        pill.setTextColor(if (state) 0xFFE53935.toInt() else 0xFF666666.toInt())
        pill.textSize = 11f
        pill.typeface = Typeface.DEFAULT_BOLD
        (v as LinearLayout).addView(pill)

        applyFocus(v, tvLabel)
        v.setOnClickListener {
            state = !state
            pill.text = if (state) "ON" else "OFF"
            pill.setTextColor(if (state) 0xFFE53935.toInt() else 0xFF666666.toInt())
            onChange(state)
        }
        body.addView(v)
        return v
    }

    /**
     * D-pad slider: shows ◄ value ► with left/right key events adjusting value.
     * Step = 1 always; min/max control the range.
     */
    fun addSlider(
        label: String,
        value: Int,
        min: Int,
        max: Int,
        displayFn: (Int) -> String = { "$it%" },
        onChange: (Int) -> Unit
    ): View {
        val container = LinearLayout(context)
        container.orientation = LinearLayout.VERTICAL
        container.isFocusable = true
        container.isClickable = true
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        container.layoutParams = lp
        container.setPadding((20 * dp).toInt(), (10 * dp).toInt(), (20 * dp).toInt(), (10 * dp).toInt())

        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = android.view.Gravity.CENTER_VERTICAL

        val tvLabel = TextView(context)
        tvLabel.text = label
        tvLabel.setTextColor(0xFFB0B0B0.toInt())
        tvLabel.textSize = 14f
        tvLabel.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(tvLabel)

        val tvLeft = TextView(context)
        tvLeft.text = "◄"
        tvLeft.setTextColor(0xFF666666.toInt())
        tvLeft.textSize = 12f
        row.addView(tvLeft)

        var current = value.coerceIn(min, max)
        val tvVal = TextView(context)
        tvVal.text = displayFn(current)
        tvVal.setTextColor(0xFFE53935.toInt())
        tvVal.textSize = 13f
        tvVal.typeface = Typeface.DEFAULT_BOLD
        tvVal.setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), 0)
        row.addView(tvVal)

        val tvRight = TextView(context)
        tvRight.text = "►"
        tvRight.setTextColor(0xFF666666.toInt())
        tvRight.textSize = 12f
        row.addView(tvRight)

        container.addView(row)

        // Visual progress bar
        val seek = SeekBar(context)
        seek.max = max - min
        seek.progress = current - min
        seek.progressTintList = android.content.res.ColorStateList.valueOf(0xFFE53935.toInt())
        seek.thumbTintList = android.content.res.ColorStateList.valueOf(0xFFE53935.toInt())
        seek.isEnabled = false  // visual only; d-pad controls it
        seek.isFocusable = false
        val seekLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        seekLp.topMargin = (4 * dp).toInt()
        seek.layoutParams = seekLp
        container.addView(seek)

        applyFocus(container, tvLabel)

        container.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (current > min) {
                        current--; tvVal.text = displayFn(current)
                        seek.progress = current - min; onChange(current)
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (current < max) {
                        current++; tvVal.text = displayFn(current)
                        seek.progress = current - min; onChange(current)
                    }
                    true
                }
                else -> false
            }
        }
        body.addView(container)
        return container
    }

    private fun applyFocus(v: View, label: TextView) {
        v.setOnFocusChangeListener { view, hasFocus ->
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(if (hasFocus) 0x1AFFFFFF.toInt() else 0x00000000)
                setStroke(if (hasFocus) (2 * dp).toInt() else 0,
                    if (hasFocus) 0xFFE53935.toInt() else 0x00000000)
                cornerRadius = 8 * dp
            }
            view.background = bg
            label.setTextColor(if (hasFocus) 0xFFFFFFFF.toInt() else 0xFFB0B0B0.toInt())
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PAGE: MAIN MENU
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildMainMenu() {
        addSectionHeader("SETTINGS")
        addEntry("All Apps", R.drawable.ic_grid) {
            navigateTo("All Apps") { buildAllApps() }
        }
        addEntry("Edit Categories", R.drawable.ic_settings_rows) {
            navigateTo("Edit Categories") { buildEditCategories() }
        }
        addEntry("Edit Channels", R.drawable.ic_channel) {
            navigateTo("Edit Channels") { buildEditChannels() }
        }
        addSeparator()
        addEntry("Shrine Settings", R.drawable.ic_settings, showArrow = true) {
            navigateTo("Shrine Settings") { buildShrineSettings() }
        }
        addEntry("Android Settings", R.drawable.ic_android, showArrow = true) {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
        addEntry("Install 3rd Party Apps", R.drawable.ic_install, showArrow = true) {
            context.startActivity(Intent(context, AppInstallerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PAGE: ALL APPS
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildAllApps() {
        pageJob = scope.launch {
            val apps = withContext(Dispatchers.IO) { appRepo.getAllApps() }
            if (!isActive) return@launch
            val inflater = LayoutInflater.from(context)
            apps.forEach { app ->
                val v       = inflater.inflate(R.layout.item_all_apps_entry, body, false)
                val ivIcon  = v.findViewById<ImageView>(R.id.ivAppIcon)
                val tvLabel = v.findViewById<TextView>(R.id.tvAppLabel)
                app.icon?.let { ivIcon.setImageDrawable(it) }
                tvLabel.text = app.label

                applyFocus(v, tvLabel)
                v.setOnClickListener {
                    appRepo.launchApp(app.packageName)
                    dismiss()
                }
                v.setOnLongClickListener {
                    navigateTo(app.label) { buildContextMenu(app) }
                    true
                }
                body.addView(v)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PAGE: EDIT CATEGORIES
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildEditCategories() {
        addEntry("+ New Category", R.drawable.ic_add) {
            val newRow = LauncherRow(
                id = "row_custom_${System.currentTimeMillis()}",
                title = "New Category",
                kind = RowKind.CATEGORY,
                categoryType = CategoryType.CUSTOM
            )
            prefRepo.addRow(newRow)
            navigateTo(newRow.title) { buildCategoryEditor(newRow) }
        }
        addSectionHeader("CATEGORIES")

        val rows = prefRepo.loadRows().filter { it.kind == RowKind.CATEGORY }
        rows.forEach { row ->
            val v       = LayoutInflater.from(context).inflate(R.layout.item_panel_menu_entry, body, false)
            val tvLabel = v.findViewById<TextView>(R.id.tvEntryLabel)
            val ivIcon  = v.findViewById<ImageView>(R.id.ivEntryIcon)
            val ivArrow = v.findViewById<ImageView>(R.id.ivEntryArrow)
            tvLabel.text = row.title
            if (!row.isVisible) tvLabel.alpha = 0.5f
            ivIcon.visibility = View.GONE; ivArrow.visibility = View.GONE
            applyFocus(v, tvLabel)
            v.setOnClickListener { navigateTo(row.title) { buildCategoryEditor(row) } }
            body.addView(v)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PAGE: CATEGORY EDITOR
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildCategoryEditor(rowIn: LauncherRow) {
        var row = rowIn
        val isBuiltIn = row.categoryType in listOf(
            CategoryType.ALL_APPS, CategoryType.FAVORITES, CategoryType.INSTALL)

        // Title field
        addSectionHeader("TITLE")
        val etTitle = EditText(context).apply {
            setText(row.title)
            setTextColor(0xFFFFFFFF.toInt())
            hint = "Category name"
            setHintTextColor(0xFF555555.toInt())
            textSize = 14f
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0x33FFFFFF)
                cornerRadius = 6 * dp
            }
            val p = (10 * dp).toInt()
            setPadding(p, p / 2, p, p / 2)
        }
        val etLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        etLp.setMargins((16 * dp).toInt(), (4 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
        etTitle.layoutParams = etLp
        body.addView(etTitle)

        // Visible toggle
        addSectionHeader("OPTIONS")
        var isVisible = row.isVisible
        addToggle("Visible", isVisible) { isVisible = it }

        var isPinned = row.isPinnedToTop
        addToggle("Pinned to top", isPinned) { isPinned = it }

        // Size picker (expands inline)
        addEntry("Icon size: ${sizeDisplayName(row.iconSizeLabelOverride)}", R.drawable.ic_grid,
            showArrow = true) {
            navigateTo("Icon Size") { buildSizePicker(row.iconSizeLabelOverride) { label ->
                row = row.copy(iconSizeLabelOverride = label)
                prefRepo.updateRow(row)
                // Return to editor with updated row
                navStack.removeLast()
                navigateTo(row.title) { buildCategoryEditor(row) }
            } }
        }

        // Manage apps (custom + favourites only)
        if (row.categoryType !in listOf(CategoryType.ALL_APPS, CategoryType.INSTALL)) {
            addEntry("Manage Apps", R.drawable.ic_settings_rows, showArrow = true) {
                navigateTo("Manage Apps") { buildManageApps(row) }
            }
        }

        // Delete (custom only)
        if (!isBuiltIn) {
            addSeparator()
            addEntry("Delete category", labelColor = 0xFFCF6679.toInt()) {
                AlertDialog.Builder(context)
                    .setTitle("Delete \"${row.title}\"?")
                    .setMessage("This cannot be undone.")
                    .setPositiveButton("Delete") { _, _ ->
                        prefRepo.removeRow(row.id)
                        onBackPressed()   // return to categories list
                    }
                    .setNegativeButton("Cancel", null).show()
            }
        }

        addSeparator()
        addEntry("Save", labelColor = 0xFFE53935.toInt()) {
            val updated = row.copy(
                title                = etTitle.text.toString().ifBlank { row.title },
                isVisible            = isVisible,
                isPinnedToTop        = isPinned
            )
            prefRepo.updateRow(updated)
            onBackPressed()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PAGE: SIZE PICKER (inline panel)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildSizePicker(current: String?, onPicked: (String?) -> Unit) {
        val sizes  = arrayOf<String?>(null, "S", "M", "L", "XL")
        val labels = arrayOf("Global (default)", "S — Small", "M — Medium", "L — Large", "XL — Extra Large")
        sizes.forEachIndexed { i, size ->
            val isSelected = size == current
            val v = addEntry(labels[i], labelColor = if (isSelected) 0xFFFFFFFF.toInt() else 0xFFB0B0B0.toInt()) {
                onPicked(size)
            }
            if (isSelected) {
                val tv = v.findViewById<TextView>(R.id.tvEntryLabel)
                tv.typeface = Typeface.DEFAULT_BOLD
            }
        }
    }

    private fun sizeDisplayName(label: String?) = when (label) {
        "S"  -> "S — Small"
        "M"  -> "M — Medium"
        "L"  -> "L — Large"
        "XL" -> "XL — Extra Large"
        else -> "Global"
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PAGE: MANAGE APPS (panel with toggles)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildManageApps(row: LauncherRow) {
        pageJob = scope.launch {
            val allApps = withContext(Dispatchers.IO) { appRepo.getAllApps() }
            if (!isActive) return@launch
            val allowed = row.apps.toMutableList()

            addSectionHeader("TAP TO TOGGLE · D-PAD TO REORDER")

            allApps.forEach { app ->
                val isIn = app.packageName in allowed
                val v    = LayoutInflater.from(context).inflate(R.layout.item_all_apps_entry, body, false)
                val iv   = v.findViewById<ImageView>(R.id.ivAppIcon)
                val tv   = v.findViewById<TextView>(R.id.tvAppLabel)
                app.icon?.let { iv.setImageDrawable(it) }
                tv.text = app.label
                tv.alpha = if (isIn) 1f else 0.4f

                // Red dot indicator when included
                val dot = TextView(context)
                dot.text = "●"
                dot.setTextColor(0xFFE53935.toInt())
                dot.textSize = 10f
                dot.visibility = if (isIn) View.VISIBLE else View.INVISIBLE
                (v as LinearLayout).addView(dot)

                applyFocus(v, tv)
                v.setOnClickListener {
                    if (app.packageName in allowed) {
                        allowed.remove(app.packageName)
                        tv.alpha = 0.4f; dot.visibility = View.INVISIBLE
                    } else {
                        allowed.add(app.packageName)
                        tv.alpha = 1f; dot.visibility = View.VISIBLE
                    }
                    prefRepo.updateRow(row.copy(apps = allowed))
                }
                body.addView(v)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PAGE: EDIT CHANNELS
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildEditChannels() {
        pageJob = scope.launch {
            val tvChannels = withContext(Dispatchers.IO) { tvRepo.listTvProviderChannels() }
            val rows       = prefRepo.loadRows()
            if (!isActive) return@launch

            // Grouped channel rows (Continue Watching, Watch Next)
            addSectionHeader("GROUPED CHANNELS")
            rows.filter { it.kind == RowKind.CHANNEL && it.channelType != ChannelType.TV_PROVIDER }
                .forEach { row ->
                    val v       = LayoutInflater.from(context).inflate(R.layout.item_panel_menu_entry, body, false)
                    val tvLabel = v.findViewById<TextView>(R.id.tvEntryLabel)
                    v.findViewById<ImageView>(R.id.ivEntryIcon).visibility = View.GONE
                    v.findViewById<ImageView>(R.id.ivEntryArrow).visibility = View.GONE
                    tvLabel.text = row.title
                    if (!row.isVisible) tvLabel.alpha = 0.5f
                    applyFocus(v, tvLabel)
                    v.setOnClickListener { navigateTo(row.title) { buildChannelEditor(row) } }
                    body.addView(v)
                }

            // Individual TvProvider channels
            addSectionHeader("APP CHANNELS")
            if (tvChannels.isEmpty()) {
                val tv = TextView(context)
                tv.text = "No app channels found"
                tv.setTextColor(0xFF555555.toInt())
                tv.textSize = 13f
                tv.setPadding((20 * dp).toInt(), (8 * dp).toInt(), 0, 0)
                body.addView(tv)
            } else {
                tvChannels.forEach { (channelId, channelName, _) ->
                    val existingRow = rows.find {
                        it.channelType == ChannelType.TV_PROVIDER && it.tvProviderChannelId == channelId
                    }
                    val v       = LayoutInflater.from(context).inflate(R.layout.item_panel_menu_entry, body, false)
                    val tvLabel = v.findViewById<TextView>(R.id.tvEntryLabel)
                    v.findViewById<ImageView>(R.id.ivEntryIcon).visibility = View.GONE
                    v.findViewById<ImageView>(R.id.ivEntryArrow).visibility = View.GONE
                    tvLabel.text = channelName
                    if (existingRow == null || !existingRow.isVisible) tvLabel.alpha = 0.5f
                    applyFocus(v, tvLabel)
                    v.setOnClickListener {
                        val row = existingRow ?: run {
                            val nr = LauncherRow(
                                id = "row_tvprovider_$channelId",
                                title = channelName,
                                kind = RowKind.CHANNEL,
                                channelType = ChannelType.TV_PROVIDER,
                                tvProviderChannelId = channelId,
                                isVisible = false
                            )
                            prefRepo.addRow(nr); nr
                        }
                        navigateTo(channelName) { buildChannelEditor(row) }
                    }
                    body.addView(v)
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PAGE: CHANNEL EDITOR
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildChannelEditor(rowIn: LauncherRow) {
        var row = rowIn

        addSectionHeader("TITLE")
        val etTitle = EditText(context).apply {
            setText(row.title)
            setTextColor(0xFFFFFFFF.toInt())
            hint = "Channel name"
            setHintTextColor(0xFF555555.toInt())
            textSize = 14f
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0x33FFFFFF); cornerRadius = 6 * dp
            }
            val p = (10 * dp).toInt()
            setPadding(p, p / 2, p, p / 2)
        }
        val etLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        etLp.setMargins((16 * dp).toInt(), (4 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
        etTitle.layoutParams = etLp
        body.addView(etTitle)

        addSectionHeader("OPTIONS")
        var isVisible = row.isVisible
        addToggle("Visible", isVisible) { isVisible = it }

        var isPinned = row.isPinnedToTop
        addToggle("Pinned to top", isPinned) { isPinned = it }

        addEntry("Icon size: ${sizeDisplayName(row.iconSizeLabelOverride)}", showArrow = true) {
            navigateTo("Icon Size") { buildSizePicker(row.iconSizeLabelOverride) { label ->
                row = row.copy(iconSizeLabelOverride = label)
                prefRepo.updateRow(row)
                navStack.removeLast()
                navigateTo(row.title) { buildChannelEditor(row) }
            } }
        }

        // Source filter only for grouped channel rows
        if (row.channelType != ChannelType.TV_PROVIDER) {
            addEntry("Source filter", showArrow = true) {
                navigateTo("Source Filter") { buildSourceFilter(row) }
            }
        }

        // Hidden items
        val dismissed = prefRepo.getDismissedForChannel(row.id).toList()
        if (dismissed.isNotEmpty()) {
            addSectionHeader("HIDDEN ITEMS — tap to restore")
            dismissed.forEach { contentId ->
                val v = addEntry(contentId, labelColor = 0xFF888888.toInt()) {
                    prefRepo.restoreDismissedForChannel(row.id, contentId)
                    // Rebuild this page
                    rawShowPage(row.title); buildChannelEditor(row)
                }
            }
        }

        addSeparator()
        addEntry("Save", labelColor = 0xFFE53935.toInt()) {
            prefRepo.updateRow(row.copy(
                title         = etTitle.text.toString().ifBlank { row.title },
                isVisible     = isVisible,
                isPinnedToTop = isPinned
            ))
            onBackPressed()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PAGE: SOURCE FILTER (panel with toggles)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildSourceFilter(row: LauncherRow) {
        pageJob = scope.launch {
            // Only list apps that have TvProvider channels
            val tvChannels = withContext(Dispatchers.IO) { tvRepo.listTvProviderChannels() }
            val channelPkgs = tvChannels.map { it.third }.distinct()
            val allApps = withContext(Dispatchers.IO) { appRepo.getAllApps() }
                .filter { it.packageName in channelPkgs }
            if (!isActive) return@launch

            if (allApps.isEmpty()) {
                val tv = TextView(context)
                tv.text = "No apps with channels found"
                tv.setTextColor(0xFF555555.toInt())
                tv.textSize = 13f
                tv.setPadding((20 * dp).toInt(), (12 * dp).toInt(), 0, 0)
                body.addView(tv)
                return@launch
            }

            addSectionHeader("TAP TO TOGGLE SOURCE")
            val allowed = row.allowedPackages.toMutableList()

            allApps.forEach { app ->
                val isAllowed = allowed.isEmpty() || app.packageName in allowed
                val v = LayoutInflater.from(context).inflate(R.layout.item_all_apps_entry, body, false)
                val iv = v.findViewById<ImageView>(R.id.ivAppIcon)
                val tv = v.findViewById<TextView>(R.id.tvAppLabel)
                app.icon?.let { iv.setImageDrawable(it) }
                tv.text = app.label
                tv.alpha = if (isAllowed) 1f else 0.4f

                val dot = TextView(context)
                dot.text = "●"; dot.setTextColor(0xFFE53935.toInt())
                dot.textSize = 10f
                dot.visibility = if (isAllowed) View.VISIBLE else View.INVISIBLE
                (v as LinearLayout).addView(dot)

                applyFocus(v, tv)
                v.setOnClickListener {
                    if (allowed.isEmpty()) {
                        // Currently "all" — explicitly add everything except this one
                        val all = allApps.map { it.packageName }.toMutableList()
                        all.remove(app.packageName)
                        allowed.clear(); allowed.addAll(all)
                    } else if (app.packageName in allowed) {
                        allowed.remove(app.packageName)
                    } else {
                        allowed.add(app.packageName)
                        if (allowed.size == allApps.size) allowed.clear() // back to "all"
                    }
                    val inNow = allowed.isEmpty() || app.packageName in allowed
                    tv.alpha = if (inNow) 1f else 0.4f
                    dot.visibility = if (inNow) View.VISIBLE else View.INVISIBLE
                    prefRepo.updateRow(row.copy(allowedPackages = allowed.toList()))
                }
                body.addView(v)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PAGE: APP CONTEXT MENU
    // ═══════════════════════════════════════════════════════════════════════════

    fun buildContextMenu(app: AppInfo) {
        val isFav = prefRepo.loadFavourites().contains(app.packageName)

        addEntry("Open", R.drawable.ic_channel) {
            if (app.packageName == "com.shrine.launcher.INSTALL_ROW") {
                context.startActivity(Intent(context, AppInstallerActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } else {
                appRepo.launchApp(app.packageName)
            }
            dismiss()
        }

        val favLabel = if (isFav) "Remove from Favourites" else "Add to Favourites"
        addEntry(favLabel, R.drawable.ic_add) {
            prefRepo.toggleFavourite(app.packageName)
            dismiss()
        }

        addEntry("App Info", R.drawable.ic_android) {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", app.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            dismiss()
        }

        addEntry("Force Stop", R.drawable.ic_install) {
            val hasPerm = context.checkSelfPermission("android.permission.FORCE_STOP_PACKAGES") ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            if (hasPerm) {
                try {
                    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                    val m  = android.app.ActivityManager::class.java
                        .getDeclaredMethod("forceStopPackage", String::class.java)
                    m.isAccessible = true; m.invoke(am, app.packageName)
                    Toast.makeText(context, "${app.label} stopped", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Force stop failed", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context,
                    "Run: adb shell pm grant com.shrine.launcher android.permission.FORCE_STOP_PACKAGES",
                    Toast.LENGTH_LONG).show()
            }
            dismiss()
        }

        addEntry("Uninstall", labelColor = 0xFFCF6679.toInt()) {
            context.startActivity(Intent(Intent.ACTION_DELETE).apply {
                data = android.net.Uri.fromParts("package", app.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            dismiss()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PAGE: SHRINE SETTINGS HUB
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildShrineSettings() {
        addSectionHeader("CONFIGURE")
        addEntry("General", R.drawable.ic_settings, showArrow = true) {
            navigateTo("General") { buildGeneral() }
        }
        addEntry("Appearance", R.drawable.ic_grid, showArrow = true) {
            navigateTo("Appearance") { buildAppearanceHub() }
        }
        addEntry("Manage Settings", R.drawable.ic_install, showArrow = true) {
            navigateTo("Manage Settings") { buildManageSettings() }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PAGE: GENERAL
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildGeneral() {
        val prefs = prefRepo.loadPrefs()

        addToggle("Enable Channels", prefs.channelsEnabled) { checked ->
            prefRepo.savePrefs(prefRepo.loadPrefs().copy(channelsEnabled = checked))
        }

        addEntry("Set as Default Launcher") {
            try {
                context.startActivity(Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e: Exception) {
                try {
                    context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (e2: Exception) {
                    Toast.makeText(context,
                        "Settings → Applications → Default Apps → Home App",
                        Toast.LENGTH_LONG).show()
                }
            }
        }

        addEntry("Check for Updates") { checkForUpdates() }
    }

    private fun checkForUpdates() {
        scope.launch {
            val tag = withContext(Dispatchers.IO) {
                runCatching {
                    JSONObject(URL("https://api.github.com/repos/ctait2007/ShrineLauncher/releases/latest")
                        .readText()).getString("tag_name").trimStart('v')
                }.getOrNull()
            }
            if (tag == null) {
                Toast.makeText(context, "Could not check for updates", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val current = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            } catch (e: Exception) { "0.0.0" }
            if (tag == current) {
                Toast.makeText(context, "Up to date (v$current)", Toast.LENGTH_SHORT).show()
            } else {
                AlertDialog.Builder(context)
                    .setTitle("Update available")
                    .setMessage("v$tag is available (you have v$current)")
                    .setPositiveButton("Install") { _, _ ->
                        val url = "https://github.com/ctait2007/ShrineLauncher/releases/download/v$tag/shrine-v$tag-beta.apk"
                        context.startActivity(Intent(context, AppInstallerActivity::class.java).apply {
                            putExtra("install_url", url)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    }
                    .setNegativeButton("Cancel", null).show()
            }
        }
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // PAGE: APPEARANCE HUB
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildAppearanceHub() {
        addEntry("Categories / Channels", showArrow = true) {
            navigateTo("Categories / Channels") { buildCategoryAppearance() }
        }
        addEntry("Cards", showArrow = true) {
            navigateTo("Cards") { buildCardAppearance() }
        }
        addEntry("Wallpaper", showArrow = true) {
            // Wallpaper needs file picker — launch Activity
            context.startActivity(Intent(context, WallpaperAppearanceActivity::class.java).apply {
                wallpaperUri?.let { putExtra("wallpaper_uri", it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
        addEntry("Status Bar", showArrow = true) {
            navigateTo("Status Bar") { buildStatusBar() }
        }
        addEntry("Idle Mode", showArrow = true) {
            navigateTo("Idle Mode") { buildIdleMode() }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PAGE: CATEGORY / CHANNEL APPEARANCE
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildCategoryAppearance() {
        val p = prefRepo.loadPrefs()

        addToggle("Show category title", p.showCategoryTitle) { checked ->
            prefRepo.savePrefs(prefRepo.loadPrefs().copy(showCategoryTitle = checked))
        }
        addToggle("Show progress bar", p.progressBarEnabled) { checked ->
            prefRepo.savePrefs(prefRepo.loadPrefs().copy(progressBarEnabled = checked))
        }
        addSectionHeader("LAYOUT")
        addSlider("Bottom margin", p.rowsBottomMarginPercent, 0, 100, { "$it%" }) { v ->
            prefRepo.savePrefs(prefRepo.loadPrefs().copy(rowsBottomMarginPercent = v))
        }
        addSlider("Start margin", p.rowStartPaddingDp.coerceIn(0, 100), 0, 100, { "$it%" }) { v ->
            prefRepo.savePrefs(prefRepo.loadPrefs().copy(rowStartPaddingDp = v))
        }
        addSlider("Row spacing", p.rowSpacingPercent, 0, 100, { "$it%" }) { v ->
            prefRepo.savePrefs(prefRepo.loadPrefs().copy(rowSpacingPercent = v))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PAGE: CARD APPEARANCE
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildCardAppearance() {
        val p = prefRepo.loadPrefs()

        addToggle("Show app title", p.showAppTitle) { checked ->
            prefRepo.savePrefs(prefRepo.loadPrefs().copy(showAppTitle = checked))
        }
        addToggle("Banner mode", p.globalCardDisplayMode == CardDisplayMode.BANNER) { checked ->
            prefRepo.savePrefs(prefRepo.loadPrefs().copy(
                globalCardDisplayMode = if (checked) CardDisplayMode.BANNER else CardDisplayMode.ICON))
        }
        addSlider("Corner roundness", p.cardCornerRadiusPercent, 0, 100, { "$it%" }) { v ->
            prefRepo.savePrefs(prefRepo.loadPrefs().copy(cardCornerRadiusPercent = v))
        }

        addSectionHeader("CARD SIZE")
        buildCardSizeButtons(p.iconSizeLabel)

        addSlider("Card spacing", p.itemSpacingPercent, 0, 100, { "$it%" }) { v ->
            prefRepo.savePrefs(prefRepo.loadPrefs().copy(itemSpacingPercent = v))
        }
    }

    private fun buildCardSizeButtons(currentSize: String) {
        val sizes = listOf("S" to "S", "M" to "M", "L" to "L", "XL" to "XL")
        val row   = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins((16 * dp).toInt(), (4 * dp).toInt(), (16 * dp).toInt(), (4 * dp).toInt())
            layoutParams = lp
        }
        sizes.forEach { (label, _) ->
            val isSelected = label == currentSize
            val btn = TextView(context).apply {
                text = label
                textSize = 13f
                gravity = android.view.Gravity.CENTER
                isFocusable = true
                isClickable = true
                val lp = LinearLayout.LayoutParams(0, (40 * dp).toInt(), 1f)
                lp.setMargins((3 * dp).toInt(), 0, (3 * dp).toInt(), 0)
                layoutParams = lp
                // #12: selected = white bg + red text; unselected = translucent + grey text
                setTextColor(if (isSelected) 0xFFE53935.toInt() else 0xFFB0B0B0.toInt())
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(if (isSelected) 0xFFFFFFFF.toInt() else 0x33FFFFFF)
                    cornerRadius = 8 * dp
                }
            }
            btn.setOnClickListener {
                prefRepo.savePrefs(prefRepo.loadPrefs().copy(iconSizeLabel = label))
                rawShowPage(currentTitle); buildCardAppearance()
            }
            btn.setOnFocusChangeListener { v, hasFocus ->
                val sel = label == prefRepo.loadPrefs().iconSizeLabel
                v.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(if (sel) 0xFFFFFFFF.toInt() else 0x33FFFFFF)
                    setStroke(if (hasFocus) (2 * dp).toInt() else 0,
                        if (hasFocus) 0xFFE53935.toInt() else 0)
                    cornerRadius = 8 * dp
                }
            }
            row.addView(btn)
        }
        body.addView(row)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PAGE: STATUS BAR
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildStatusBar() {
        val p = prefRepo.loadPrefs()
        addToggle("Show clock", p.clockEnabled) { c -> prefRepo.savePrefs(prefRepo.loadPrefs().copy(clockEnabled = c)) }
        addToggle("Show date",  p.dateEnabled)  { c -> prefRepo.savePrefs(prefRepo.loadPrefs().copy(dateEnabled  = c)) }
        addToggle("24-hour clock", p.clockFormat24h) { c -> prefRepo.savePrefs(prefRepo.loadPrefs().copy(clockFormat24h = c)) }
        addEntry("Wi-Fi settings") {
            context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        }
        addSlider("Status bar size", p.statusBarIconSizePercent, 50, 150, { "$it%" }) { v ->
            prefRepo.savePrefs(prefRepo.loadPrefs().copy(statusBarIconSizePercent = v))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PAGE: IDLE MODE
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildIdleMode() {
        val p = prefRepo.loadPrefs()
        addToggle("Enable idle mode", p.idleModeEnabled) { c ->
            prefRepo.savePrefs(prefRepo.loadPrefs().copy(idleModeEnabled = c))
        }
        addSlider("Idle timeout", p.idleTimeoutSeconds, 30, 300, { s ->
            if (s < 60) "${s}s" else "${s / 60}m${if (s % 60 > 0) " ${s % 60}s" else ""}"
        }) { v -> prefRepo.savePrefs(prefRepo.loadPrefs().copy(idleTimeoutSeconds = v)) }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PAGE: MANAGE SETTINGS
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildManageSettings() {
        addSectionHeader("LOCAL")
        addEntry("Export to file") {
            context.startActivity(Intent(context, ManageSettingsActivity::class.java).apply {
                putExtra("action", "export"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
        addEntry("Import from file") {
            context.startActivity(Intent(context, ManageSettingsActivity::class.java).apply {
                putExtra("action", "import"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
        addSectionHeader("CLOUD")
        addEntry("Share config") {
            val json = ConfigManager.exportToString(context)
            if (json != null) {
                context.startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, json)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }, "Share Shrine config").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            } else {
                Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
            }
        }
        addEntry("Import from clipboard") {
            val cm    = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text  = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
            if (text.isNullOrBlank()) {
                Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            } else {
                val ok = ConfigManager.importFromString(context, text)
                Toast.makeText(context,
                    if (ok) "Settings imported from clipboard" else "Invalid config in clipboard",
                    Toast.LENGTH_SHORT).show()
            }
        }
        addSeparator()
        addEntry("Reset to defaults", labelColor = 0xFFCF6679.toInt()) {
            AlertDialog.Builder(context)
                .setTitle("Reset to defaults?")
                .setMessage("All settings and row config will be reset. Cannot be undone.")
                .setPositiveButton("Reset") { _, _ ->
                    prefRepo.savePrefs(com.shrine.launcher.data.model.LauncherPrefs())
                    Toast.makeText(context, "Reset complete", Toast.LENGTH_SHORT).show()
                    dismiss()
                }
                .setNegativeButton("Cancel", null).show()
        }
    }
}
