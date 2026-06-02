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
import com.shrine.launcher.adb.AdbManager
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

class SettingsPanelDialog(
    context: Context,
    private val wallpaperUri: String? = null,
    private val onDismissed: (() -> Unit)? = null,
    private val initialApp: AppInfo? = null,          // if set, open directly to context menu
    private val initialRow: LauncherRow? = null,      // if set, open directly to that row's editor
    private val onBrowseForApk: (() -> Unit)? = null, // triggers HomeActivity's file picker
    private val onSettingsChanged: (() -> Unit)? = null
) : Dialog(context) {

    // ── Repos ──────────────────────────────────────────────────────────────────
    private val prefRepo by lazy { PreferencesRepository.getInstance(context) }
    private val appRepo  by lazy { AppRepository.getInstance(context) }
    private val tvRepo   by lazy { TvContentRepository.getInstance(context) }
    private val scope    = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pageJob: Job? = null

    // ── Navigation ─────────────────────────────────────────────────────────────
    private data class NavEntry(val title: String?, val builder: () -> Unit, val focusedIndex: Int = -1)
    private val navStack = ArrayDeque<NavEntry>()
    private var currentTitle: String? = null
    private var currentBuilder: () -> Unit = {}
    // Body-child index to restore focus to after rebuilding a page (set by onBackPressed)
    private var pendingFocusRestoreIndex = -1

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

        tvPageTitle.textSize = 13f
        tvPageTitle.typeface = Typeface.DEFAULT_BOLD

        // Compact header: logo height ≈ 55% of what 16:9 would give, centred inside
        ivLogo.post {
            val w = ivLogo.width
            if (w > 0) {
                val lp = ivLogo.layoutParams
                lp.height = (w * 9f / 16f * 0.55f).toInt()
                ivLogo.layoutParams = lp
            }
        }

        setOnDismissListener { onDismissed?.invoke() }

        // Apply rounded corners after the view is laid out
        body.post { applyRoundedCorners() }

        when {
            initialApp != null ->
                showPage(initialApp.label, false) { buildContextMenu(initialApp) }
            initialRow != null ->
                showPage(initialRow.title, false) {
                    if (initialRow.kind == RowKind.CHANNEL) buildChannelEditor(initialRow)
                    else buildCategoryEditor(initialRow)
                }
            else ->
                showPage(null, false) { buildMainMenu() }
        }
    }

    private fun configureWindow() {
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.END or Gravity.CENTER_VERTICAL)
            val dm = context.resources.displayMetrics
            val w  = (dm.widthPixels  * 0.26f).toInt()
            val h  = (dm.heightPixels * 0.94f).toInt()
            setLayout(w, h)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // Gap between panel right edge and screen right edge
            val attrs = attributes
            attrs.x = (20 * context.resources.displayMetrics.density).toInt()
            attributes = attrs
        }
    }

    // Applied after layout is measured so width is known
    private fun applyRoundedCorners() {
        val root = findViewById<View>(R.id.panelRoot) ?: return
        val radius = 16f * dp
        root.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        root.clipToOutline = true
    }

    override fun onBackPressed() {
        notifyChanged()   // apply any deferred setting changes on page exit
        if (navStack.isEmpty()) { dismiss(); return }
        val prev = navStack.removeLast()
        currentTitle   = prev.title
        currentBuilder = prev.builder
        pendingFocusRestoreIndex = prev.focusedIndex   // focusFirstItem() will use this
        rawShowPage(prev.title)
        prev.builder()
        focusFirstItem()
    }

    override fun onStop() { super.onStop(); scope.cancel() }

    // ── Navigation core ────────────────────────────────────────────────────────

    /** Push the current page to the back-stack and show a new page. */
    fun navigateTo(title: String, builder: () -> Unit) {
        // Capture which body child has focus so Back can return to it
        val focusedIdx = body.findFocus()?.let { focused ->
            (0 until body.childCount).firstOrNull { i ->
                val child = body.getChildAt(i)
                child === focused || child.findFocus() === focused
            }
        } ?: -1
        navStack.addLast(NavEntry(currentTitle, currentBuilder, focusedIdx))
        currentTitle   = title
        currentBuilder = builder
        rawShowPage(title)
        builder()
        focusFirstItem()
    }

    private fun showPage(title: String?, push: Boolean = true, builder: () -> Unit) {
        if (push) {
            navStack.addLast(NavEntry(currentTitle, currentBuilder))
        }
        currentTitle   = title
        currentBuilder = builder
        rawShowPage(title)
        builder()
        focusFirstItem()
    }

    /** Call whenever a setting value is persisted. */
    private fun notifyChanged() { onSettingsChanged?.invoke() }

    private fun rawShowPage(title: String?) {
        pageJob?.cancel()
        pageJob = null
        body.removeAllViews()
        // Logo always visible; subheading shown only on sub-pages
        ivLogo.visibility = View.VISIBLE
        if (title == null) {
            tvPageTitle.visibility = View.GONE
        } else {
            tvPageTitle.visibility = View.VISIBLE
            tvPageTitle.text       = title
        }
    }

    /** Focus the pending restore index (set by Back navigation) or the first focusable child. */
    private fun focusFirstItem() {
        body.post {
            // If body is still empty (async page not yet loaded), leave state intact so the
            // coroutine's own focusFirstItem() call picks it up once views are added.
            val hasContent = (0 until body.childCount).any { i ->
                body.getChildAt(i).let { c -> c.isFocusable && c.visibility == View.VISIBLE }
            }
            if (!hasContent) return@post

            val idx = pendingFocusRestoreIndex
            pendingFocusRestoreIndex = -1

            if (idx in 0 until body.childCount) {
                val target = body.getChildAt(idx)
                if (target != null && target.isFocusable && target.visibility == View.VISIBLE) {
                    target.requestFocus()
                    return@post
                }
            }
            // Fallback: first focusable non-EditText child
            for (i in 0 until body.childCount) {
                val child = body.getChildAt(i)
                if (child.isFocusable && child !is EditText && child.visibility == View.VISIBLE) {
                    child.requestFocus()
                    return@post
                }
            }
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
                ivIcon.setColorFilter(0xFFC06060.toInt())   // softer muted red tint
                ivIcon.visibility = View.VISIBLE
            } catch (e: Exception) { ivIcon.visibility = View.GONE }
        } else {
            ivIcon.visibility = View.GONE
        }
        ivArrow.visibility = if (showArrow) View.VISIBLE else View.GONE

        applyFocus(v, tvLabel, labelColor)
        onClick?.let { v.setOnClickListener { it() } }
        body.addView(v)
        return v
    }

    /** Toggle row with optional icon: label on left, ON/OFF pill on right. */
    fun addToggle(label: String, iconRes: Int = 0, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val v       = LayoutInflater.from(context).inflate(R.layout.item_panel_menu_entry, body, false)
        val tvLabel = v.findViewById<TextView>(R.id.tvEntryLabel)
        val ivIcon  = v.findViewById<ImageView>(R.id.ivEntryIcon)
        val ivArrow = v.findViewById<ImageView>(R.id.ivEntryArrow)
        tvLabel.text = label
        ivArrow.visibility = View.GONE
        if (iconRes != 0) {
            ivIcon.setImageResource(iconRes)
            ivIcon.setColorFilter(0xFFC06060.toInt())
            ivIcon.visibility = View.VISIBLE
        } else {
            ivIcon.visibility = View.GONE
        }

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
        step: Int = 1,
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
        seek.max = (max - min) / step
        seek.progress = (current - min) / step
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
                        current = (current - step).coerceAtLeast(min)
                        tvVal.text = displayFn(current)
                        seek.progress = (current - min) / step; onChange(current)
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (current < max) {
                        current = (current + step).coerceAtMost(max)
                        tvVal.text = displayFn(current)
                        seek.progress = (current - min) / step; onChange(current)
                    }
                    true
                }
                else -> false
            }
        }
        body.addView(container)
        return container
    }

    private fun applyFocus(v: View, label: TextView, defaultColor: Int = 0xFFB0B0B0.toInt()) {
        v.setOnFocusChangeListener { view, hasFocus ->
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(if (hasFocus) 0x1AFFFFFF.toInt() else 0x00000000)
                setStroke(if (hasFocus) (2 * dp).toInt() else 0,
                    if (hasFocus) 0xFFE53935.toInt() else 0x00000000)
                cornerRadius = 8 * dp
            }
            view.background = bg
            label.setTextColor(if (hasFocus) 0xFFFFFFFF.toInt() else defaultColor)
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
        addEntry("Shrine Settings", R.drawable.ic_settings, showArrow = true) {
            navigateTo("Shrine Settings") { buildShrineSettings() }
        }
        addEntry("Android Settings", R.drawable.ic_android, showArrow = true) {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
        addEntry("Install 3rd Party Apps", R.drawable.ic_install, showArrow = true) {
            navigateTo("Install Apps") { buildInstallerPage() }
        }
        addEntry("ADB Shell", R.drawable.ic_tune, showArrow = true) {
            navigateTo("ADB Shell") { buildAdbShellPage() }
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
            focusFirstItem()
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
        addToggle("Visible", R.drawable.ic_visibility_on, isVisible) { isVisible = it }

        var isPinned = row.isPinnedToTop
        addToggle("Pinned to top", R.drawable.ic_pin, isPinned) { isPinned = it }

        // Size picker: inline dropdown that toggles open/closed
        val sizeLabels = arrayOf<String?>(null, "S", "M", "L", "XL")
        val sizeNames  = arrayOf("Global", "S — Small", "M — Medium", "L — Large", "XL — Extra Large")
        var sizeExpanded = false
        val sizeOptionViews = mutableListOf<View>()

        val sizeHeaderEntry = addEntry("Icon size: ${sizeDisplayName(row.iconSizeLabelOverride)}",
            R.drawable.ic_grid, showArrow = false) { }

        // Pre-create size option rows (hidden initially)
        sizeLabels.forEachIndexed { i, sz ->
            val optView = LayoutInflater.from(context).inflate(R.layout.item_panel_menu_entry, body, false)
            val tvOpt   = optView.findViewById<TextView>(R.id.tvEntryLabel)
            optView.findViewById<ImageView>(R.id.ivEntryIcon).visibility = View.GONE
            optView.findViewById<ImageView>(R.id.ivEntryArrow).visibility = View.GONE
            tvOpt.text = "    ${sizeNames[i]}"
            if (sz == row.iconSizeLabelOverride) {
                tvOpt.setTextColor(0xFFFFFFFF.toInt())
                tvOpt.typeface = Typeface.DEFAULT_BOLD
            } else {
                tvOpt.setTextColor(0xFFB0B0B0.toInt())
            }
            optView.visibility = View.GONE
            applyFocus(optView, tvOpt)
            optView.setOnClickListener {
                row = row.copy(iconSizeLabelOverride = sz)
                prefRepo.updateRow(row); notifyChanged()
                // Collapse and update header label
                sizeExpanded = false
                sizeOptionViews.forEach { it.visibility = View.GONE }
                sizeHeaderEntry.findViewById<TextView>(R.id.tvEntryLabel)?.text =
                    "Icon size: ${sizeDisplayName(sz)}"
                sizeOptionViews.forEach { v ->
                    val tv2 = v.findViewById<TextView>(R.id.tvEntryLabel)
                    val isNowSel = sizeLabels[sizeOptionViews.indexOf(v)] == sz
                    tv2.setTextColor(if (isNowSel) 0xFFFFFFFF.toInt() else 0xFFB0B0B0.toInt())
                    tv2.typeface = if (isNowSel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                }
            }
            sizeOptionViews.add(optView)
            body.addView(optView)
        }

        sizeHeaderEntry.setOnClickListener {
            sizeExpanded = !sizeExpanded
            sizeOptionViews.forEach { it.visibility = if (sizeExpanded) View.VISIBLE else View.GONE }
        }

        // Manage apps (custom + favourites only)
        if (row.categoryType !in listOf(CategoryType.ALL_APPS, CategoryType.INSTALL)) {
            addEntry("Manage Apps", R.drawable.ic_settings_rows, showArrow = true) {
                manageOrder   = row.apps.toMutableList()
                manageAllowed = row.apps.toMutableList()
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

    // Live state for Manage Apps
    // manageOrder  = visual order of the Shown section (up/down moves change this)
    // manageAllowed = which packages are currently enabled (pill toggle changes this)
    private var manageOrder   = mutableListOf<String>()
    private var manageAllowed = mutableListOf<String>()

    private fun buildManageApps(
        row: LauncherRow,
        focusPkg: String? = null,
        focusTarget: String = "up"   // "up" | "down" | "pill"
    ) {
        pageJob = scope.launch {
            val allApps = withContext(Dispatchers.IO) { appRepo.getAllApps() }
            if (!isActive) return@launch

            // Initialise from row.apps on first entry; subsequent rebuilds keep session state
            if (manageOrder.isEmpty() && row.apps.isNotEmpty()) {
                manageOrder   = row.apps.toMutableList()
                manageAllowed = row.apps.toMutableList()
            }

            // Shown section rendered in manageOrder (visual order, changed by up/down).
            // manageAllowed tracks enabled/disabled state independently.
            val pendingOff  = manageOrder.filter { it !in manageAllowed }.toSet()
            val shownApps   = manageOrder.mapNotNull { pkg -> allApps.find { it.packageName == pkg } }
            val newlyAdded  = manageAllowed.filter { it !in manageOrder }
                .mapNotNull { pkg -> allApps.find { it.packageName == pkg } }
            val hiddenApps  = allApps
                .filter { it.packageName !in manageOrder && it.packageName !in manageAllowed }
                .sortedBy { it.label.lowercase() }

            addSectionHeader("SHOWN")
            renderManageSection(shownApps + newlyAdded, pendingOff, row, true)

            addSectionHeader("HIDDEN")
            renderManageSection(hiddenApps, emptySet(), row, false)

            body.post {
                if (focusPkg != null) {
                    for (i in 0 until body.childCount) {
                        val child = body.getChildAt(i)
                        if (child.tag == focusPkg) {
                            val btn: View? = when (focusTarget) {
                                "down" -> child.findViewById(R.id.btnMoveDown)
                                "pill" -> child.findViewById(R.id.tvManagePill)
                                else   -> child.findViewById(R.id.btnMoveUp)
                            }
                            if (btn?.visibility == View.VISIBLE) { btn.requestFocus(); return@post }
                            // Fell through (e.g. up on first item) — fall to default
                            break
                        }
                    }
                }
                // Default: focus up arrow of first shown-and-on app
                for (i in 0 until body.childCount) {
                    val btn = body.getChildAt(i)?.findViewById<View>(R.id.btnMoveUp)
                    if (btn?.visibility == View.VISIBLE) { btn.requestFocus(); return@post }
                }
            }
        }
    }

    private fun makePillSlider(isOn: Boolean, focused: Boolean = false): android.graphics.drawable.LayerDrawable {
        val track = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 100f * dp
            setColor(if (isOn) 0xFFE53935.toInt() else 0xFF444444.toInt())
            if (focused) setStroke((2 * dp).toInt(), 0xFFFFFFFF.toInt())
        }
        val thumb = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFFFFFFF.toInt())
        }
        val layers = android.graphics.drawable.LayerDrawable(arrayOf(track, thumb))
        val thumbDiam = (14 * dp).toInt()
        val hPad = (4 * dp).toInt()
        val vPad = ((22 * dp - thumbDiam) / 2).toInt()
        val trackW = (40 * dp).toInt()
        if (isOn) layers.setLayerInset(1, trackW - thumbDiam - hPad, vPad, hPad, vPad)
        else       layers.setLayerInset(1, hPad, vPad, trackW - thumbDiam - hPad, vPad)
        return layers
    }

    private fun renderManageSection(
        apps: List<com.shrine.launcher.data.model.AppInfo>,
        pendingOff: Set<String>,     // shown in "shown" section but toggled off this session
        row: LauncherRow,
        isShownSection: Boolean
    ) {
        val inflater = LayoutInflater.from(context)
        apps.forEach { app ->
            val isPendingOff = app.packageName in pendingOff
            var isOn = isShownSection && !isPendingOff

            val v       = inflater.inflate(R.layout.item_manage_app_entry, body, false)
            val iv      = v.findViewById<ImageView>(R.id.ivManageIcon)
            val tv      = v.findViewById<TextView>(R.id.tvManageLabel)
            val pill    = v.findViewById<View>(R.id.tvManagePill)
            val btnUp   = v.findViewById<TextView>(R.id.btnMoveUp)
            val btnDown = v.findViewById<TextView>(R.id.btnMoveDown)

            v.tag = app.packageName  // used to restore focus after rebuild
            app.icon?.let { iv.setImageDrawable(it) }
            tv.text = app.label

            pill.background = makePillSlider(isOn)

            pill.setOnFocusChangeListener { _, hasFocus ->
                pill.background = makePillSlider(isOn, focused = hasFocus)
            }
            pill.setOnClickListener {
                isOn = !isOn
                // Pill only changes enabled state; position in manageOrder is preserved
                if (isOn) manageAllowed.add(app.packageName)
                else      manageAllowed.remove(app.packageName)
                prefRepo.updateRow(row.copy(apps = manageOrder.filter { it in manageAllowed }.toMutableList()))
                rawShowPage(currentTitle); buildManageApps(row, focusPkg = app.packageName, focusTarget = "pill")
            }

            // Reorder buttons visible only for shown-and-ON items
            if (isShownSection && !isPendingOff) {
                btnUp.visibility   = View.VISIBLE
                btnDown.visibility = View.VISIBLE

                btnUp.setOnClickListener {
                    val idx = manageOrder.indexOf(app.packageName)
                    if (idx > 0) {
                        val otherPkg = manageOrder[idx - 1]
                        manageOrder.removeAt(idx); manageOrder.add(idx - 1, app.packageName)
                        prefRepo.updateRow(row.copy(apps = manageOrder.filter { it in manageAllowed }.toMutableList()))
                        swapManageRows(app.packageName, otherPkg, pkg1MovedUp = true)
                    }
                }
                btnDown.setOnClickListener {
                    val idx = manageOrder.indexOf(app.packageName)
                    if (idx in 0 until manageOrder.size - 1) {
                        val otherPkg = manageOrder[idx + 1]
                        manageOrder.removeAt(idx); manageOrder.add(idx + 1, app.packageName)
                        prefRepo.updateRow(row.copy(apps = manageOrder.filter { it in manageAllowed }.toMutableList()))
                        swapManageRows(app.packageName, otherPkg, pkg1MovedUp = false)
                    }
                }
                applyButtonFocus(btnUp)
                applyButtonFocus(btnDown)
            } else {
                btnUp.visibility   = View.GONE
                btnDown.visibility = View.GONE
            }

            body.addView(v)
        }
    }

    private fun applyButtonFocus(v: View) {
        v.setOnFocusChangeListener { view, hasFocus ->
            view.setBackgroundColor(if (hasFocus) 0x33FFFFFF else 0x00000000)
        }
    }

    /**
     * Swap two manage-app row views in body without rebuilding the list.
     * pkg1 is the app that moved; after swap, its btnMoveUp/Down gets focus.
     */
    private fun swapManageRows(pkg1: String, pkg2: String, pkg1MovedUp: Boolean) {
        var view1: View? = null; var idx1 = -1
        var view2: View? = null; var idx2 = -1
        for (i in 0 until body.childCount) {
            val child = body.getChildAt(i)
            when (child.tag) {
                pkg1 -> { view1 = child; idx1 = i }
                pkg2 -> { view2 = child; idx2 = i }
            }
        }
        if (view1 == null || view2 == null || idx1 == -1 || idx2 == -1) return
        val lo = minOf(idx1, idx2); val hi = maxOf(idx1, idx2)
        val loView = if (idx1 < idx2) view1 else view2
        val hiView = if (idx1 < idx2) view2 else view1
        body.removeViewAt(hi); body.removeViewAt(lo)
        body.addView(hiView, lo); body.addView(loView, hi)
        // Restore focus on the moved app's button
        val btnId = if (pkg1MovedUp) R.id.btnMoveUp else R.id.btnMoveDown
        view1.findViewById<View>(btnId)?.takeIf { it.visibility == View.VISIBLE }?.requestFocus()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PAGE: EDIT CHANNELS
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildEditChannels() {
        pageJob = scope.launch {
            val tvChannels = withContext(Dispatchers.IO) { tvRepo.listTvProviderChannels() }
            val rows       = prefRepo.loadRows()
            val allApps    = withContext(Dispatchers.IO) { appRepo.getAllApps() }
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

            // App Channels: one entry per app; clicking drills into that app's channels
            addSectionHeader("APP CHANNELS")
            if (tvChannels.isEmpty()) {
                val tv = TextView(context)
                tv.text = "No app channels found"
                tv.setTextColor(0xFF555555.toInt())
                tv.textSize = 12f
                tv.setPadding((20 * dp).toInt(), (8 * dp).toInt(), 0, 0)
                body.addView(tv)
            } else {
                val byApp = tvChannels.groupBy { it.third }
                byApp.forEach { (pkg, channels) ->
                    val appInfo  = allApps.find { it.packageName == pkg }
                    val appLabel = appInfo?.label ?: try {
                        context.packageManager.getApplicationLabel(
                            context.packageManager.getApplicationInfo(pkg, 0)).toString()
                    } catch (e: Exception) { pkg.substringAfterLast('.') }

                    val v       = LayoutInflater.from(context).inflate(R.layout.item_all_apps_entry, body, false)
                    val ivIcon  = v.findViewById<ImageView>(R.id.ivAppIcon)
                    val tvLabel = v.findViewById<TextView>(R.id.tvAppLabel)
                    appInfo?.icon?.let { ivIcon.setImageDrawable(it) }
                    tvLabel.text = appLabel

                    applyFocus(v, tvLabel)
                    v.setOnClickListener {
                        navigateTo(appLabel) { buildAppChannelList(channels, rows) }
                    }
                    body.addView(v)
                }
            }
            focusFirstItem()
        }
    }

    private fun buildAppChannelList(
        channels: List<Triple<Long, String, String>>,
        rows: List<LauncherRow>
    ) {
        channels.forEach { (channelId, channelName, _) ->
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
        addToggle("Visible", R.drawable.ic_visibility_on, isVisible) { isVisible = it }

        var isPinned = row.isPinnedToTop
        addToggle("Pinned to top", R.drawable.ic_pin, isPinned) { isPinned = it }

        // Inline size dropdown (same pattern as category editor)
        val chSizeLabels = arrayOf<String?>(null, "S", "M", "L", "XL")
        val chSizeNames  = arrayOf("Global", "S — Small", "M — Medium", "L — Large", "XL — Extra Large")
        var chSizeExpanded = false
        val chSizeOptionViews = mutableListOf<View>()
        val chSizeHeader = addEntry("Icon size: ${sizeDisplayName(row.iconSizeLabelOverride)}", R.drawable.ic_grid, showArrow = false) { }
        chSizeLabels.forEachIndexed { i, sz ->
            val ov  = LayoutInflater.from(context).inflate(R.layout.item_panel_menu_entry, body, false)
            val tvO = ov.findViewById<TextView>(R.id.tvEntryLabel)
            ov.findViewById<ImageView>(R.id.ivEntryIcon).visibility = View.GONE
            ov.findViewById<ImageView>(R.id.ivEntryArrow).visibility = View.GONE
            tvO.text = "    ${chSizeNames[i]}"
            if (sz == row.iconSizeLabelOverride) { tvO.setTextColor(0xFFFFFFFF.toInt()); tvO.typeface = Typeface.DEFAULT_BOLD }
            else tvO.setTextColor(0xFFB0B0B0.toInt())
            ov.visibility = View.GONE
            applyFocus(ov, tvO)
            ov.setOnClickListener {
                row = row.copy(iconSizeLabelOverride = sz)
                prefRepo.updateRow(row); notifyChanged()
                chSizeExpanded = false
                chSizeOptionViews.forEach { it.visibility = View.GONE }
                chSizeHeader.findViewById<TextView>(R.id.tvEntryLabel)?.text = "Icon size: ${sizeDisplayName(sz)}"
            }
            chSizeOptionViews.add(ov); body.addView(ov)
        }
        chSizeHeader.setOnClickListener {
            chSizeExpanded = !chSizeExpanded
            chSizeOptionViews.forEach { it.visibility = if (chSizeExpanded) View.VISIBLE else View.GONE }
        }

        // Source filter only for grouped channel rows
        if (row.channelType != ChannelType.TV_PROVIDER) {
            addEntry("Source filter", showArrow = true) {
                navigateTo("Source Filter") { buildSourceFilter(row) }
            }
        }

        // Hidden items
        val dismissed = prefRepo.getDismissedWithTitles(row.id)
        if (dismissed.isNotEmpty()) {
            addSectionHeader("HIDDEN ITEMS — tap to restore")
            dismissed.forEach { (contentId, contentTitle) ->
                addEntry(contentTitle, labelColor = 0xFF888888.toInt()) {
                    prefRepo.restoreDismissedForChannel(row.id, contentId)
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
            val adb = AdbManager.getInstance(context)
            if (adb.state != AdbManager.AdbState.CONNECTED) {
                Toast.makeText(context, "Connect ADB in Settings → ADB Shell to use Force Stop", Toast.LENGTH_LONG).show()
                dismiss()
                return@addEntry
            }
            scope.launch {
                val result = adb.executeShell("am force-stop ${app.packageName}")
                Toast.makeText(context,
                    if (result.exitCode == 0) "${app.label} stopped"
                    else "Force stop failed: ${result.output}",
                    if (result.exitCode == 0) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                dismiss()
            }
        }

        addEntry("Uninstall", labelColor = 0xFFCF6679.toInt()) {
            scope.launch {
                val adb = AdbManager.getInstance(context)
                val ok = if (adb.isConnected()) {
                    val result = adb.executeShell("pm uninstall ${app.packageName}")
                    result.exitCode == 0
                } else false
                if (ok) {
                    Toast.makeText(context, "${app.label} uninstalled", Toast.LENGTH_SHORT).show()
                } else {
                    context.startActivity(Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
                        data = android.net.Uri.parse("package:${app.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
                dismiss()
            }
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

    // Held between buildGeneral() and checkForUpdateInline() calls within the same page visit
    private var updateStatusView: TextView? = null
    private var updateInstallView: View? = null

    private fun buildGeneral() {
        val prefs = prefRepo.loadPrefs()
        updateStatusView = null
        updateInstallView = null

        addToggle("Enable Channels", checked = prefs.channelsEnabled) { checked ->
            prefRepo.savePrefs(prefRepo.loadPrefs().copy(channelsEnabled = checked)); notifyChanged()
        }

        addEntry("Set as Default Launcher") {
            val intents = listOf(
                Intent(Settings.ACTION_HOME_SETTINGS),
                Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
                Intent(android.provider.Settings.ACTION_SETTINGS)
            )
            var launched = false
            for (i in intents) {
                try {
                    context.startActivity(i.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                    launched = true; break
                } catch (_: Exception) {}
            }
            if (!launched) {
                Toast.makeText(context,
                    "Settings → Applications → Default Apps → Home App → Shrine Launcher",
                    Toast.LENGTH_LONG).show()
            }
        }

        addEntry("Check for Updates") { checkForUpdateInline() }

        // Status text — hidden until a check is run
        val tvStatus = TextView(context).apply {
            setTextColor(0xFF888888.toInt())
            textSize = 11f
            setPadding((20 * dp).toInt(), (2 * dp).toInt(), (20 * dp).toInt(), (2 * dp).toInt())
            visibility = View.GONE
        }
        body.addView(tvStatus)
        updateStatusView = tvStatus

        // Install button — hidden until an update is found
        val btnInstall = LayoutInflater.from(context).inflate(R.layout.item_panel_menu_entry, body, false)
        val btnLabel   = btnInstall.findViewById<TextView>(R.id.tvEntryLabel)
        val btnIcon    = btnInstall.findViewById<android.widget.ImageView>(R.id.ivEntryIcon)
        val btnArrow   = btnInstall.findViewById<android.widget.ImageView>(R.id.ivEntryArrow)
        btnLabel.text = "Install update"
        btnLabel.setTextColor(0xFFE53935.toInt())
        btnIcon.visibility  = View.GONE
        btnArrow.visibility = View.GONE
        btnInstall.visibility = View.GONE
        applyFocus(btnInstall, btnLabel, 0xFFE53935.toInt())
        body.addView(btnInstall)
        updateInstallView = btnInstall
    }

    private fun checkForUpdateInline() {
        updateStatusView?.let { it.visibility = View.VISIBLE; it.text = "Checking…"; it.setTextColor(0xFF888888.toInt()) }
        updateInstallView?.visibility = View.GONE

        scope.launch {
            val tag = withContext(Dispatchers.IO) {
                runCatching {
                    JSONObject(URL("https://api.github.com/repos/ctait2007/ShrineLauncher/releases/latest")
                        .readText()).getString("tag_name").trimStart('v')
                }.getOrNull()
            }
            if (tag == null) {
                updateStatusView?.let { it.text = "Could not check for updates"; it.setTextColor(0xFFCF6679.toInt()) }
                return@launch
            }
            val current = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
            } catch (e: Exception) { "0.0.0" }

            if (tag == current) {
                updateStatusView?.let { it.text = "Up to date — v$tag"; it.setTextColor(0xFF888888.toInt()) }
            } else {
                updateStatusView?.let { it.text = "Update available — v$tag"; it.setTextColor(0xFFE53935.toInt()) }
                val url = "https://github.com/ctait2007/ShrineLauncher/releases/download/v$tag/shrine-v$tag-beta.apk"
                updateInstallView?.let { btn ->
                    btn.visibility = View.VISIBLE
                    btn.setOnClickListener {
                        context.startActivity(Intent(context, AppInstallerActivity::class.java).apply {
                            putExtra("install_url", url)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    }
                }
            }
        }
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // PAGE: APPEARANCE HUB
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildAppearanceHub() {
        addEntry("Categories / Channels", R.drawable.ic_channel, showArrow = true) {
            navigateTo("Categories / Channels") { buildCategoryAppearance() }
        }
        addEntry("Cards", R.drawable.ic_grid, showArrow = true) {
            navigateTo("Cards") { buildCardAppearance() }
        }
        addEntry("Wallpaper", R.drawable.ic_image, showArrow = true) {
            navigateTo("Wallpaper") { buildWallpaperPage() }
        }
        addEntry("Status Bar", R.drawable.ic_status_bar, showArrow = true) {
            navigateTo("Status Bar") { buildStatusBar() }
        }
        addEntry("Idle Mode", R.drawable.ic_sleep, showArrow = true) {
            navigateTo("Idle Mode") { buildIdleMode() }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PAGE: WALLPAPER
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildWallpaperPage() {
        val p = prefRepo.loadPrefs()
        val hasSingle   = !p.wallpaperUri.isNullOrBlank()
        val hasSlideshow = p.wallpaperUris.isNotEmpty()

        addToggle("Slideshow mode", checked = p.wallpaperSlideshow) { checked ->
            prefRepo.savePrefs(prefRepo.loadPrefs().copy(wallpaperSlideshow = checked)); notifyChanged()
        }

        val currentDesc = when {
            hasSlideshow -> "${p.wallpaperUris.size} images"
            hasSingle    -> "1 image set"
            else         -> "None"
        }
        addEntry("Select wallpaper(s)  ($currentDesc)") {
            // WallpaperPickerActivity is transparent — only shows the system file picker
            context.startActivity(Intent(context,
                com.shrine.launcher.ui.settings.WallpaperPickerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }

        val hasWallpaper = hasSingle || hasSlideshow
        addEntry(
            "Remove wallpaper",
            labelColor = if (hasWallpaper) 0xFFCF6679.toInt() else 0xFF444444.toInt()
        ) {
            if (!hasWallpaper) return@addEntry
            prefRepo.savePrefs(prefRepo.loadPrefs().copy(
                wallpaperUri = null, wallpaperUris = emptyList(), wallpaperSlideshow = false
            )); notifyChanged()
            rawShowPage(currentTitle); buildWallpaperPage()
        }

        val intervals = listOf(30 to "30s", 60 to "1 min", 120 to "2 min",
            300 to "5 min", 600 to "10 min", 900 to "15 min", 1800 to "30 min")
        val currInterval = p.wallpaperIntervalSeconds
        val currLabel = intervals.find { it.first == currInterval }?.second ?: "5 min"
        var intervalExpanded = false
        val intervalOptionViews = mutableListOf<View>()

        val intervalHeader = addEntry("Slide interval: $currLabel") { }
        intervals.forEach { (secs, label) ->
            val ov = LayoutInflater.from(context).inflate(R.layout.item_panel_menu_entry, body, false)
            val tvO = ov.findViewById<TextView>(R.id.tvEntryLabel)
            ov.findViewById<ImageView>(R.id.ivEntryIcon).visibility = View.GONE
            ov.findViewById<ImageView>(R.id.ivEntryArrow).visibility = View.GONE
            tvO.text = "    $label"
            if (secs == currInterval) { tvO.setTextColor(0xFFFFFFFF.toInt()); tvO.typeface = Typeface.DEFAULT_BOLD }
            else tvO.setTextColor(0xFFB0B0B0.toInt())
            ov.visibility = View.GONE
            applyFocus(ov, tvO)
            ov.setOnClickListener {
                prefRepo.savePrefs(prefRepo.loadPrefs().copy(wallpaperIntervalSeconds = secs)); notifyChanged()
                intervalExpanded = false; intervalOptionViews.forEach { it.visibility = View.GONE }
                intervalHeader.findViewById<TextView>(R.id.tvEntryLabel)?.text = "Slide interval: $label"
            }
            intervalOptionViews.add(ov); body.addView(ov)
        }
        intervalHeader.setOnClickListener {
            intervalExpanded = !intervalExpanded
            intervalOptionViews.forEach { it.visibility = if (intervalExpanded) View.VISIBLE else View.GONE }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PAGE: CATEGORY / CHANNEL APPEARANCE
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildCategoryAppearance() {
        val p = prefRepo.loadPrefs()

        addToggle("Show category title", R.drawable.ic_visibility_on, p.showCategoryTitle) { checked ->
            prefRepo.savePrefs(prefRepo.loadPrefs().copy(showCategoryTitle = checked)); notifyChanged()
        }
        addToggle("Show progress bar", checked = p.progressBarEnabled) { checked ->
            prefRepo.savePrefs(prefRepo.loadPrefs().copy(progressBarEnabled = checked)); notifyChanged()
        }
        addSectionHeader("LAYOUT")
        addSlider("Bottom margin", p.rowsBottomMarginPercent, 0, 100, displayFn = { "$it%" }) { v ->
            prefRepo.savePrefs(prefRepo.loadPrefs().copy(rowsBottomMarginPercent = v)); notifyChanged()
        }
        addSlider("Start margin", p.rowStartPaddingDp.coerceIn(0, 100), 0, 100, displayFn = { "$it%" }) { v ->
            prefRepo.savePrefs(prefRepo.loadPrefs().copy(rowStartPaddingDp = v)); notifyChanged()
        }
        addSlider("Row spacing", p.rowSpacingPercent, 0, 100, displayFn = { "$it%" }) { v ->
            prefRepo.savePrefs(prefRepo.loadPrefs().copy(rowSpacingPercent = v)); notifyChanged()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PAGE: CARD APPEARANCE
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildCardAppearance() {
        val p = prefRepo.loadPrefs()

        addToggle("Show app title", R.drawable.ic_visibility_on, p.showAppTitle) { checked ->
            prefRepo.savePrefs(prefRepo.loadPrefs().copy(showAppTitle = checked)); notifyChanged()
        }
        addToggle("Banner mode", checked = p.globalCardDisplayMode == CardDisplayMode.BANNER) { checked ->
            prefRepo.savePrefs(prefRepo.loadPrefs().copy(
                globalCardDisplayMode = if (checked) CardDisplayMode.BANNER else CardDisplayMode.ICON))
        }
        addSlider("Corner roundness", p.cardCornerRadiusPercent, 0, 100, displayFn = { "$it%" }) { v ->
            prefRepo.savePrefs(prefRepo.loadPrefs().copy(cardCornerRadiusPercent = v)); notifyChanged()
        }

        addSectionHeader("CARD SIZE")
        buildCardSizeButtons(p.iconSizeLabel)

        addSlider("Card spacing", p.itemSpacingPercent, 0, 100, displayFn = { "$it%" }) { v ->
            prefRepo.savePrefs(prefRepo.loadPrefs().copy(itemSpacingPercent = v)); notifyChanged()
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
                prefRepo.savePrefs(prefRepo.loadPrefs().copy(iconSizeLabel = label)); notifyChanged()
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
        addToggle("Show Wi-Fi button", R.drawable.ic_wifi, p.showWifiButton) { c ->
            prefRepo.savePrefs(prefRepo.loadPrefs().copy(showWifiButton = c)); notifyChanged()
        }
        addToggle("Show clock", R.drawable.ic_visibility_on, p.clockEnabled) { c -> prefRepo.savePrefs(prefRepo.loadPrefs().copy(clockEnabled = c)); notifyChanged() }
        addToggle("Show date",  R.drawable.ic_visibility_on, p.dateEnabled)  { c -> prefRepo.savePrefs(prefRepo.loadPrefs().copy(dateEnabled  = c)); notifyChanged() }
        addToggle("24-hour clock", checked = p.clockFormat24h) { c -> prefRepo.savePrefs(prefRepo.loadPrefs().copy(clockFormat24h = c)); notifyChanged() }
        // Status bar size: deferred — notifyChanged() fires on back press, not per-tick
        addSlider("Status bar size", p.statusBarIconSizePercent, 50, 150, displayFn = { "$it%" }) { v ->
            prefRepo.savePrefs(prefRepo.loadPrefs().copy(statusBarIconSizePercent = v))
            // no notifyChanged() here — applied on section exit
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PAGE: IDLE MODE
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildIdleMode() {
        val p = prefRepo.loadPrefs()
        addToggle("Enable idle mode", R.drawable.ic_sleep, p.idleModeEnabled) { c ->
            prefRepo.savePrefs(prefRepo.loadPrefs().copy(idleModeEnabled = c)); notifyChanged()
        }
        addSlider("Idle timeout", p.idleTimeoutSeconds, 30, 300, step = 30, displayFn = { s ->
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
                    notifyChanged()
                    Toast.makeText(context, "Reset complete", Toast.LENGTH_SHORT).show()
                    dismiss()
                }
                .setNegativeButton("Cancel", null).show()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PAGE: INLINE INSTALLER
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildInstallerPage() {
        addSectionHeader("INSTALL FROM URL")

        val etUrl = EditText(context).apply {
            hint = "https://example.com/app.apk"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF555555.toInt())
            textSize = 13f
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
        etUrl.layoutParams = etLp
        body.addView(etUrl)

        val tvStatus = TextView(context).apply {
            setTextColor(0xFF888888.toInt())
            textSize = 11f
            setPadding((20 * dp).toInt(), 0, (20 * dp).toInt(), (4 * dp).toInt())
        }
        body.addView(tvStatus)

        addEntry("Install from URL", R.drawable.ic_install, labelColor = 0xFFE53935.toInt()) {
            val url = etUrl.text.toString().trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                tvStatus.text = "Enter a valid http(s) URL"
                return@addEntry
            }
            tvStatus.text = "Downloading…"
            pageJob = scope.launch {
                val adb = AdbManager.getInstance(context)
                val tmpPath = "/data/local/tmp/shrine_install.apk"

                // If shell is connected, have it create a world-writable file in /data/local/tmp
                // so the app process can write to it (app lacks directory write on /data/local/tmp)
                val useTmp = adb.isConnected() &&
                    adb.executeShell("touch $tmpPath && chmod 666 $tmpPath").exitCode == 0

                val dest = if (useTmp) java.io.File(tmpPath)
                           else java.io.File(context.cacheDir, "shrine_install.apk")

                val downloaded = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                        conn.instanceFollowRedirects = true; conn.connect()
                        val total = conn.contentLength; var done = 0
                        conn.inputStream.use { input ->
                            java.io.FileOutputStream(dest).use { out ->
                                val buf = ByteArray(8192); var n: Int
                                while (input.read(buf).also { n = it } != -1) {
                                    out.write(buf, 0, n); done += n
                                    if (total > 0) {
                                        val pct = done * 100 / total
                                        withContext(kotlinx.coroutines.Dispatchers.Main) { tvStatus.text = "Downloading… $pct%" }
                                    }
                                }
                            }
                        }
                        conn.disconnect(); true
                    } catch (e: Exception) { false }
                }
                if (!downloaded) { tvStatus.text = "Download failed — check the URL"; return@launch }

                tvStatus.text = "Installing…"
                if (useTmp) {
                    val result = adb.executeShell("pm install -r $tmpPath", 60_000L)
                    adb.executeShell("rm -f $tmpPath")
                    val ok = result.exitCode == 0 || result.output.contains("Success", ignoreCase = true)
                    tvStatus.text = if (ok) "✓ Installed successfully"
                                    else "Install failed: ${result.output}"
                    if (ok) {
                        kotlinx.coroutines.delay(1000)
                        onSettingsChanged?.invoke()
                    }
                } else {
                    tvStatus.text = "Opening system installer…"
                    try {
                        val fileUri = androidx.core.content.FileProvider.getUriForFile(
                            context, "${context.packageName}.fileprovider", dest)
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                            setDataAndType(fileUri, "application/vnd.android.package-archive")
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    } catch (e: Exception) { tvStatus.text = "Install failed: ${e.message}" }
                }
            }
        }

        addSeparator()
        addSectionHeader("PICK FROM DEVICE")
        addEntry("Browse for APK file", R.drawable.ic_install, showArrow = true) {
            onBrowseForApk?.invoke()
        }

    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PAGE: ADB SHELL
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildAdbShellPage() {
        val adb = AdbManager.getInstance(context)

        if (!adb.hasPermission()) {
            val tvStatus = android.widget.TextView(context).apply {
                text = "Not connected"; setTextColor(0xFFE53935.toInt())
                textSize = 11f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), (4 * dp).toInt())
            }
            body.addView(tvStatus)
            val tvPerm = android.widget.TextView(context).apply {
                text = "One-time setup required. Run from your Mac:\n\nadb shell pm grant com.shrine.launcher android.permission.WRITE_SECURE_SETTINGS\n\nThen re-open this panel."
                setTextColor(0xFFE53935.toInt()); textSize = 10f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding((20 * dp).toInt(), (4 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())
            }
            body.addView(tvPerm)
            return
        }

        if (adb.state == AdbManager.AdbState.CONNECTED) {
            // ── Connected: SHELL at top, disconnect at bottom ─────────────────
            addSectionHeader("SHELL")

            body.addView(android.widget.TextView(context).apply {
                text = "Connected"; setTextColor(0xFF4CAF50.toInt())
                textSize = 11f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding((20 * dp).toInt(), (2 * dp).toInt(), (20 * dp).toInt(), (4 * dp).toInt())
            })

            val etCmd = makeEditText("").also {
                it.hint = "am force-stop com.example.app"
                it.textSize = 12f
            }
            body.addView(etCmd)

            val tvOutput = android.widget.TextView(context).apply {
                setTextColor(0xFF9CCC65.toInt()); textSize = 10f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding((20 * dp).toInt(), (4 * dp).toInt(), (20 * dp).toInt(), (4 * dp).toInt())
            }
            body.addView(tvOutput)

            addEntry("Run", R.drawable.ic_arrow_right, labelColor = 0xFFE53935.toInt()) {
                val cmd = etCmd.text.toString().trim()
                if (cmd.isBlank()) { tvOutput.text = "Enter a command"; return@addEntry }
                tvOutput.text = "Running…"
                pageJob = scope.launch {
                    val result = adb.executeShell(cmd)
                    tvOutput.text = result.output.ifEmpty { "(no output)" }
                }
            }

            addSeparator()
            addEntry("Disconnect", labelColor = 0xFFE53935.toInt()) {
                adb.doDisconnect(); rawShowPage(currentTitle); buildAdbShellPage()
            }
        } else {
            // ── Not connected: status + connect ───────────────────────────────
            val statusColor = if (adb.state == AdbManager.AdbState.CONNECTING) 0xFF888888.toInt()
                              else 0xFFE53935.toInt()
            val statusText = if (adb.state == AdbManager.AdbState.CONNECTING) "Connecting…"
                             else "Not connected"
            val tvStatus = android.widget.TextView(context).apply {
                text = statusText; setTextColor(statusColor)
                textSize = 11f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), (4 * dp).toInt())
            }
            body.addView(tvStatus)

            val tvConnResult = android.widget.TextView(context).apply {
                setTextColor(0xFF888888.toInt()); textSize = 10f
                setPadding((20 * dp).toInt(), (2 * dp).toInt(), (20 * dp).toInt(), (2 * dp).toInt())
            }
            body.addView(tvConnResult)

            addEntry("Connect", R.drawable.ic_install, labelColor = 0xFFE53935.toInt()) {
                tvConnResult.setTextColor(0xFF888888.toInt())
                tvConnResult.text = "Starting shell…"
                tvStatus.text = "Connecting…"; tvStatus.setTextColor(0xFF888888.toInt())
                pageJob = scope.launch {
                    val result = adb.doConnect()
                    if (result.success) {
                        rawShowPage(currentTitle); buildAdbShellPage()
                    } else {
                        tvConnResult.setTextColor(0xFFE53935.toInt())
                        tvConnResult.text = result.error ?: "Connection failed"
                        tvStatus.text = "Not connected"; tvStatus.setTextColor(0xFFE53935.toInt())
                    }
                }
            }
        }
    }

    private fun makeEditText(initial: String): EditText = EditText(context).apply {
        setText(initial)
        setTextColor(0xFFFFFFFF.toInt())
        setHintTextColor(0xFF555555.toInt())
        textSize = 13f
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0x33FFFFFF); cornerRadius = 6 * dp
        }
        val p = (10 * dp).toInt()
        setPadding(p, p / 2, p, p / 2)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins((16 * dp).toInt(), (3 * dp).toInt(), (16 * dp).toInt(), (3 * dp).toInt())
        layoutParams = lp
    }

    private fun addLabeledField(label: String, field: EditText) {
        val tvLabel = android.widget.TextView(context).apply {
            text = label
            setTextColor(0xFF888888.toInt()); textSize = 10f
            setPadding((20 * dp).toInt(), (4 * dp).toInt(), 0, 0)
        }
        body.addView(tvLabel)
        body.addView(field)
    }
}
