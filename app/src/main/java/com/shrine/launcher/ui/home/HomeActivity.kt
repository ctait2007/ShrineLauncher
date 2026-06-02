package com.shrine.launcher.ui.home

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import android.view.KeyEvent
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.shrine.launcher.data.model.*
import com.shrine.launcher.databinding.ActivityHomeBinding
import com.shrine.launcher.util.ThemeUtil
import com.shrine.launcher.util.TvDatabaseObserver
import com.shrine.launcher.util.TvPermissionUtil
import java.text.SimpleDateFormat
import java.util.*

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val vm: HomeViewModel by viewModels()
    private lateinit var rowsAdapter: RowsAdapter
    private var clockTimer: Timer? = null
    private var lastWallpaperUri: String? = "__unset__"
    private var slideshowJob: kotlinx.coroutines.Job? = null
    private var slideshowIndex = 0
    private var initialFocusSet = false

    // Idle mode
    private val idleHandler = Handler(Looper.getMainLooper())
    private var isIdle = false
    private val idleRunnable = Runnable { enterIdle() }
    private var activePanel: SettingsPanelDialog? = null
    private var preIdleFocusedView: android.view.View? = null

    // Last focused card inside rvRows — captured in dispatchKeyEvent before any focus change
    private var lastRowsFocus: java.lang.ref.WeakReference<View>? = null

    // Cached layout config used to build the current RowsAdapter.
    // setupRows() skips adapter recreation if none of these have changed,
    // so card views stay attached and focus is preserved across panel open/close.
    private data class AdapterLayoutConfig(
        val cornerRadius: Int, val iconSize: String,
        val rowStart: Int, val itemSpacing: Int, val rowSpacing: Int
    )
    private var lastAdapterConfig: AdapterLayoutConfig? = null
    private fun View.isRowsDescendant(): Boolean {
        var p: android.view.ViewParent? = parent
        while (p != null) { if (p === binding.rvRows) return true; p = p.parent }
        return false
    }

    // APK file picker for inline install (item 6)
    private val pickApkLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { installApkFromUri(it) } }

    private val tvObserver = TvDatabaseObserver { vm.loadAll() }

    private val tvPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        TvPermissionUtil.checkAndRequestAccess(this)
        vm.loadAll()
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_PACKAGE_ADDED) {
                val pkg = intent.data?.schemeSpecificPart
                vm.recentlyInstalledPackage = pkg
                if (pkg != null) vm.addPackageToDefaultRow(pkg)
            }
            vm.loadAll()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupRows()
        setupClock()
        setupObservers()
        setupButtons()
        requestTvPermissions()
    }

    override fun onStart() {
        super.onStart()
        registerPackageReceiver()
        val adb = com.shrine.launcher.adb.AdbManager.getInstance(this)
        if (adb.hasPermission() && adb.state == com.shrine.launcher.adb.AdbManager.AdbState.DISCONNECTED) {
            lifecycleScope.launch { adb.doConnect() }
        }
        try {
            contentResolver.registerContentObserver(
                androidx.tvprovider.media.tv.TvContractCompat.WatchNextPrograms.CONTENT_URI,
                true, tvObserver
            )
            contentResolver.registerContentObserver(
                androidx.tvprovider.media.tv.TvContractCompat.Channels.CONTENT_URI,
                true, tvObserver
            )
            contentResolver.registerContentObserver(
                androidx.tvprovider.media.tv.TvContractCompat.PreviewPrograms.CONTENT_URI,
                true, tvObserver
            )
        } catch (e: Exception) { /* permission not yet granted */ }
    }

    override fun onStop() {
        super.onStop()
        try { contentResolver.unregisterContentObserver(tvObserver) } catch (e: Exception) { }
        try { unregisterReceiver(packageReceiver) } catch (e: Exception) { }
        idleHandler.removeCallbacks(idleRunnable)
    }

    // ── TV Permissions ─────────────────────────────────────────────────────────

    private fun requestTvPermissions() {
        val tvPerms = listOf(
            "android.permission.READ_TV_LISTINGS",
            "com.android.providers.tv.permission.READ_EPG_DATA"
        )
        val needed = tvPerms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            tvPermissionLauncher.launch(needed.toTypedArray())
        } else {
            TvPermissionUtil.checkAndRequestAccess(this)
            vm.loadAll()
        }
    }

    // ── Rows ───────────────────────────────────────────────────────────────────

    private fun setupRows() {
        val prefs = vm.prefs.value
        val config = AdapterLayoutConfig(
            cornerRadius = prefs?.cardCornerRadiusPercent ?: 50,
            iconSize     = prefs?.iconSizeLabel ?: "M",
            rowStart     = ((prefs?.rowStartPaddingDp ?: 20) * 120 / 100),
            itemSpacing  = prefs?.itemSpacingPercent ?: 10,
            rowSpacing   = prefs?.rowSpacingPercent ?: 20
        )
        // Skip adapter recreation if layout config is unchanged — preserves ViewHolders and focus
        if (config == lastAdapterConfig) return
        lastAdapterConfig = config

        rowsAdapter = RowsAdapter(
            onAppClick             = { app -> handleAppClick(app) },
            onAppLongClick         = { app -> showAppContextMenu(app) },
            onContentClick         = { content -> launchContent(content) },
            onContentLongClick     = { content -> showContentContextMenu(content) },
            onRowFocused           = { _ -> },
            onRowSettingsClick     = { row -> openPanelAtRowEditor(row) },
            onRowDisplayModeToggle = { row ->
                vm.updateRowDisplayMode(row.id,
                    if (row.cardDisplayMode == CardDisplayMode.ICON)
                        CardDisplayMode.BANNER else CardDisplayMode.ICON)
            },
            onRowMoveUp = { row ->
                val items = rowsAdapter.currentList
                val idx = items.indexOfFirst { it.launcherRow.id == row.id }
                if (idx > 0) {
                    val newItems = items.toMutableList()
                    val t = newItems[idx]; newItems[idx] = newItems[idx - 1]; newItems[idx - 1] = t
                    rowsAdapter.pendingMoveFocusRowId = row.id
                    rowsAdapter.submitList(ArrayList(newItems))
                    vm.reorderRows(newItems.map { it.launcherRow })
                }
            },
            onRowMoveDown = { row ->
                val items = rowsAdapter.currentList
                val idx = items.indexOfFirst { it.launcherRow.id == row.id }
                if (idx >= 0 && idx < items.size - 1) {
                    val newItems = items.toMutableList()
                    val t = newItems[idx]; newItems[idx] = newItems[idx + 1]; newItems[idx + 1] = t
                    rowsAdapter.pendingMoveFocusRowId = row.id
                    rowsAdapter.submitList(ArrayList(newItems))
                    vm.reorderRows(newItems.map { it.launcherRow })
                }
            },
            onPanelsCollapsed = {
                val target = lastRowsFocus?.get()
                if (target != null && target.isAttachedToWindow && target.isFocusable) {
                    target.requestFocus()
                } else {
                    binding.rvRows.post { focusFirstCardOfFirstVisibleRow() }
                }
            },
            cornerRadiusPercent    = config.cornerRadius,
            iconSizeDp             = iconSizeDp(config.iconSize),
            rowStartPaddingDp      = config.rowStart,
            itemSpacingDp          = config.itemSpacing,
            rowSpacingDp           = config.rowSpacing
        )
        binding.rvRows.apply {
            layoutManager = LinearLayoutManager(this@HomeActivity)
            adapter = rowsAdapter
            setHasFixedSize(false)
            isFocusable = false
            clipChildren = false
            clipToPadding = false
        }
    }

    private fun focusFirstCardOfFirstVisibleRow() {
        val lm = binding.rvRows.layoutManager as? LinearLayoutManager ?: return
        for (i in 0 until binding.rvRows.childCount) {
            val child = binding.rvRows.getChildAt(i) ?: continue
            val rvApps = child.findViewById<androidx.recyclerview.widget.RecyclerView>(com.shrine.launcher.R.id.rvApps) ?: continue
            if (rvApps.visibility != View.VISIBLE) continue
            val innerLm = rvApps.layoutManager as? LinearLayoutManager ?: continue
            val firstItem = innerLm.findViewByPosition(0) ?: continue
            val card = firstItem.findViewById<View>(com.shrine.launcher.R.id.cardRoot) ?: firstItem
            card.requestFocus()
            return
        }
    }

    // ── Observers ──────────────────────────────────────────────────────────────

    private fun setupObservers() {
        vm.rows.observe(this) { rebuildRows() }
        vm.allApps.observe(this) { rebuildRows() }
        vm.recentApps.observe(this) { rebuildRows() }
        vm.favourites.observe(this) { rebuildRows() }
        vm.continueWatching.observe(this) { rebuildRows() }
        vm.watchNext.observe(this) { rebuildRows() }
        vm.newForYou.observe(this) { rebuildRows() }
        vm.tvProviderContent.observe(this) { rebuildRows() }
        vm.theme.observe(this) { theme -> ThemeUtil.applyToActivity(this, binding.root, theme) }
        vm.prefs.observe(this) { prefs ->
            if (prefs == null) return@observe
            applyClock(prefs)
            applyBottomMargin(prefs)
            applyStatusBarSize(prefs)
            applyWifiButton(prefs)
            setupIdleMode(prefs)
            setupRows()
            rebuildRows()
            val uris = prefs.wallpaperUris
            val singleUri = prefs.wallpaperUri
            val allUris = when {
                uris.isNotEmpty() -> uris
                !singleUri.isNullOrBlank() -> listOf(singleUri)
                else -> emptyList()
            }
            val uriKey = allUris.joinToString(",") + prefs.wallpaperIntervalSeconds
            if (uriKey != lastWallpaperUri) {
                lastWallpaperUri = uriKey
                stopSlideshow()
                if (allUris.isEmpty()) {
                    com.bumptech.glide.Glide.with(this@HomeActivity).clear(binding.ivWallpaper)
                    binding.ivWallpaper.setImageDrawable(null)
                    binding.ivWallpaper.visibility = View.INVISIBLE
                } else if (allUris.size == 1) {
                    binding.ivWallpaper.visibility = View.VISIBLE
                    applyWallpaper(allUris[0])
                } else {
                    binding.ivWallpaper.visibility = View.VISIBLE
                    startSlideshow(allUris, prefs.wallpaperIntervalSeconds)
                }
            }
        }
    }

    private fun rebuildRows() {
        val rows  = vm.rows.value ?: return
        val prefs = vm.prefs.value
        val sorted = rows.sortedWith(compareByDescending { it.isPinnedToTop })

        val items = sorted.mapNotNull { row ->
            when (row.kind) {
                RowKind.CATEGORY -> {
                    val apps = vm.getAppsForRow(row)
                    // Hide empty categories (except ALL_APPS, INSTALL which always show)
                    if (apps.isEmpty() &&
                        row.categoryType != CategoryType.ALL_APPS &&
                        row.categoryType != CategoryType.INSTALL) return@mapNotNull null
                    RowItem.CategoryRow(row, apps)
                }
                RowKind.CHANNEL -> {
                    if (prefs?.channelsEnabled == false) return@mapNotNull null
                    RowItem.ChannelRow(row, vm.getTvContentForRow(row))
                }
            }
        }

        rowsAdapter.submitList(items) {
            if (!initialFocusSet && items.any { it is RowItem.CategoryRow }) {
                initialFocusSet = true
                val vto = binding.rvRows.viewTreeObserver
                vto.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        binding.rvRows.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        setInitialRowFocus()
                    }
                })
            }
        }
    }

    private fun setInitialRowFocus() {
        val lm = binding.rvRows.layoutManager as? LinearLayoutManager ?: return
        val list = rowsAdapter.currentList
        val appsRvId   = com.shrine.launcher.R.id.rvApps
        val cardRootId = com.shrine.launcher.R.id.cardRoot

        // Prefer ALL_APPS row; fall back to first non-empty category row
        val targetIndex = list.indexOfFirst { it is RowItem.CategoryRow && it.apps.isNotEmpty() &&
            (it as RowItem.CategoryRow).row.categoryType == com.shrine.launcher.data.model.CategoryType.ALL_APPS
        }.takeIf { it >= 0 }
            ?: list.indexOfFirst { it is RowItem.CategoryRow && (it as RowItem.CategoryRow).apps.isNotEmpty() }
                .takeIf { it >= 0 }
            ?: run { initialFocusSet = false; return }

        val rowView = lm.findViewByPosition(targetIndex) ?: run { initialFocusSet = false; return }
        val rvApps  = rowView.findViewById<androidx.recyclerview.widget.RecyclerView>(appsRvId) ?: return
        if (rvApps.visibility != View.VISIBLE) return
        val innerLm = rvApps.layoutManager as? LinearLayoutManager ?: return
        val firstItem = innerLm.findViewByPosition(0) ?: return
        val card = firstItem.findViewById<View>(cardRootId) ?: firstItem
        card.requestFocus()
    }

    // ── Wallpaper / Slideshow ──────────────────────────────────────────────────

    private fun applyWallpaper(uriString: String) {
        try {
            com.bumptech.glide.Glide.with(this)
                .load(android.net.Uri.parse(uriString))
                .centerCrop()
                .transition(com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade(600))
                .into(binding.ivWallpaper)
        } catch (e: Exception) {
            android.util.Log.w("HomeActivity", "Wallpaper load failed: ${e.message}")
        }
    }

    private fun startSlideshow(uris: List<String>, intervalSeconds: Int) {
        slideshowIndex = 0
        applyWallpaper(uris[0])
        slideshowJob = lifecycleScope.launch {
            while (true) {
                delay(intervalSeconds * 1000L)
                slideshowIndex = (slideshowIndex + 1) % uris.size
                applyWallpaper(uris[slideshowIndex])
            }
        }
    }

    private fun stopSlideshow() {
        slideshowJob?.cancel()
        slideshowJob = null
    }

    private fun applyBottomMargin(prefs: LauncherPrefs) {
        val screenH = resources.displayMetrics.heightPixels
        val pct     = prefs.rowsBottomMarginPercent.coerceIn(0, 90)
        val topPad  = (screenH * pct / 100f).toInt()
        binding.mainScroll.setPadding(0, topPad, 0, 0)
        binding.mainScroll.clipToPadding = false
    }

    private fun applyStatusBarSize(prefs: LauncherPrefs) {
        val scale = prefs.statusBarIconSizePercent.coerceIn(50, 150) / 100f
        binding.btnSettings.scaleX = scale
        binding.btnSettings.scaleY = scale
        binding.tvClock.textSize = 20f * scale
        binding.tvDate.textSize  = 12f * scale
        // Wi-Fi button scale too
        binding.btnWifi.scaleX = scale
        binding.btnWifi.scaleY = scale
    }

    private fun applyWifiButton(prefs: LauncherPrefs) {
        binding.btnWifi.visibility = if (prefs.showWifiButton) View.VISIBLE else View.GONE
    }

    // ── Idle Mode ──────────────────────────────────────────────────────────────

    private fun setupIdleMode(prefs: LauncherPrefs) {
        idleHandler.removeCallbacks(idleRunnable)
        if (prefs.idleModeEnabled) {
            idleHandler.postDelayed(idleRunnable, prefs.idleTimeoutSeconds * 1000L)
        }
    }

    private fun enterIdle() {
        preIdleFocusedView = currentFocus
        activePanel?.dismiss()
        activePanel = null
        isIdle = true
        binding.topBar.visibility    = View.INVISIBLE
        binding.mainScroll.visibility = View.INVISIBLE
    }

    private fun exitIdle() {
        isIdle = false
        binding.topBar.visibility    = View.VISIBLE
        binding.mainScroll.visibility = View.VISIBLE
        val focusTarget = preIdleFocusedView
        preIdleFocusedView = null
        if (focusTarget != null && focusTarget.isAttachedToWindow) {
            focusTarget.requestFocus()
        } else {
            binding.btnSettings.requestFocus()
        }
        val prefs = vm.prefs.value
        if (prefs?.idleModeEnabled == true) {
            idleHandler.removeCallbacks(idleRunnable)
            idleHandler.postDelayed(idleRunnable, prefs.idleTimeoutSeconds * 1000L)
        }
    }

    private fun resetIdleTimer() {
        val prefs = vm.prefs.value ?: return
        if (!prefs.idleModeEnabled) return
        idleHandler.removeCallbacks(idleRunnable)
        idleHandler.postDelayed(idleRunnable, prefs.idleTimeoutSeconds * 1000L)
    }

    // ── Clock ──────────────────────────────────────────────────────────────────

    private fun setupClock() {
        clockTimer = Timer()
        clockTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() { runOnUiThread { updateClock() } }
        }, 0, 60_000)
    }

    private fun applyClock(prefs: LauncherPrefs) {
        binding.tvClock.visibility = if (prefs.clockEnabled) View.VISIBLE else View.GONE
        binding.tvDate.visibility  = if (prefs.dateEnabled)  View.VISIBLE else View.GONE
        updateClock()
    }

    private fun updateClock() {
        val prefs = vm.prefs.value ?: return
        val fmt   = if (prefs.clockFormat24h) "HH:mm" else "h:mm a"
        binding.tvClock.text = SimpleDateFormat(fmt, Locale.getDefault()).format(Date())
        binding.tvDate.text  = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date())
    }

    // ── Buttons ────────────────────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnSettings.setOnClickListener { openPanel() }

        binding.btnWifi.setOnClickListener {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }

        // Status bar focus boundaries — consume d-pad keys that would escape the bar
        binding.btnSettings.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> true
                KeyEvent.KEYCODE_DPAD_LEFT  -> binding.btnWifi.visibility != View.VISIBLE
                KeyEvent.KEYCODE_DPAD_DOWN  -> { focusFirstCardOfRow(0); true }
                else -> false
            }
        }
        binding.btnWifi.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> true
                KeyEvent.KEYCODE_DPAD_DOWN -> { focusFirstCardOfRow(0); true }
                else -> false
            }
        }

        binding.btnWifi.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            val dp = v.resources.displayMetrics.density
            val bg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(0x00000000)
                setStroke(if (hasFocus) (2 * dp).toInt() else 0,
                    if (hasFocus) 0xFFFFFFFF.toInt() else 0x00000000)
                cornerRadius = 8 * dp
            }
            v.background = bg
        }
        binding.btnSettings.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            val dp = v.resources.displayMetrics.density
            val bg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(0x00000000)
                setStroke(if (hasFocus) (2 * dp).toInt() else 0,
                    if (hasFocus) 0xFFFFFFFF.toInt() else 0x00000000)
                cornerRadius = 8 * dp
            }
            v.background = bg
        }
    }

    // ── App / Content interactions ─────────────────────────────────────────────

    private fun handleAppClick(app: AppInfo) {
        if (app.packageName == "com.shrine.launcher.INSTALL_ROW") {
            startActivity(Intent(this, com.shrine.launcher.ui.installer.AppInstallerActivity::class.java))
        } else {
            vm.launchApp(app.packageName)
        }
    }

    private fun openPanel(initialApp: AppInfo? = null) {
        val wallpaperUri = vm.prefs.value?.wallpaperUri
            ?: vm.prefs.value?.wallpaperUris?.firstOrNull()
        val dialog = SettingsPanelDialog(
            context           = this,
            wallpaperUri      = wallpaperUri,
            onDismissed       = {
                activePanel = null
                val target = lastRowsFocus?.get()
                if (target != null && target.isAttachedToWindow) {
                    target.requestFocus()
                } else {
                    binding.rvRows.requestFocus()
                }
                vm.loadAll()
            },
            initialApp        = initialApp,
            onBrowseForApk    = { pickApkLauncher.launch(arrayOf("*/*")) },
            onSettingsChanged = { vm.loadAll() }
        )
        activePanel = dialog
        dialog.show()
    }

    private fun installApkFromUri(uri: Uri) {
        lifecycleScope.launch {
            Toast.makeText(this@HomeActivity, "Copying APK…", Toast.LENGTH_SHORT).show()
            val adb = com.shrine.launcher.adb.AdbManager.getInstance(this@HomeActivity)
            val tmpPath = "/data/local/tmp/shrine_browse_install.apk"

            // Shell creates a world-writable file so the app can write to /data/local/tmp
            val useTmp = adb.isConnected() &&
                adb.executeShell("touch $tmpPath && chmod 666 $tmpPath").exitCode == 0

            val dest = java.io.File(if (useTmp) tmpPath else cacheDir.path + "/shrine_browse_install.apk")

            val copied = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        java.io.FileOutputStream(dest).use { out -> input.copyTo(out) }
                    }
                    true
                } catch (e: Exception) { false }
            }

            if (!copied) {
                Toast.makeText(this@HomeActivity, "Failed to copy APK", Toast.LENGTH_SHORT).show()
                return@launch
            }

            if (useTmp) {
                val result = adb.executeShell("pm install -r $tmpPath", 60_000L)
                adb.executeShell("rm -f $tmpPath")
                val ok = result.exitCode == 0 || result.output.contains("Success", ignoreCase = true)
                Toast.makeText(this@HomeActivity,
                    if (ok) "APK installed successfully"
                    else "Install failed: ${result.output}",
                    Toast.LENGTH_LONG).show()
                if (ok) {
                    kotlinx.coroutines.delay(1000)
                    vm.loadAll()
                }
            } else {
                try {
                    val fileUri = androidx.core.content.FileProvider.getUriForFile(
                        this@HomeActivity, "${packageName}.fileprovider", dest)
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                        setDataAndType(fileUri, "application/vnd.android.package-archive")
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (e: Exception) {
                    Toast.makeText(this@HomeActivity, "Install failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showAppContextMenu(app: AppInfo) {
        openPanel(initialApp = app)
    }

    private fun showContentContextMenu(content: TvContent) {
        ContentContextMenuDialog(this, content,
            onPlay    = { launchContent(content) },
            onDismiss = { vm.dismissChannelContent(content) }
        ).show()
    }

    private fun openPanelAtRowEditor(row: LauncherRow) {
        val wallpaperUri = vm.prefs.value?.wallpaperUri
            ?: vm.prefs.value?.wallpaperUris?.firstOrNull()
        val dialog = SettingsPanelDialog(
            context           = this,
            wallpaperUri      = wallpaperUri,
            initialRow        = row,
            onDismissed       = {
                activePanel = null
                val target = lastRowsFocus?.get()
                if (target != null && target.isAttachedToWindow) target.requestFocus()
                else binding.rvRows.requestFocus()
                vm.loadAll()
            },
            onSettingsChanged = { vm.loadAll() }
        )
        activePanel = dialog
        dialog.show()
    }

    private fun showRowSettingsDialog(row: LauncherRow) {
        val apps = vm.allApps.value
        if (apps.isNullOrEmpty()) {
            val obs = object : androidx.lifecycle.Observer<List<AppInfo>> {
                override fun onChanged(loaded: List<AppInfo>) {
                    if (loaded.isNotEmpty()) {
                        vm.allApps.removeObserver(this)
                        RowSettingsDialog(
                            context   = this@HomeActivity,
                            row       = row,
                            allApps   = loaded,
                            onChanged = { vm.loadAll() }
                        ).show()
                    }
                }
            }
            vm.allApps.observe(this, obs)
        } else {
            RowSettingsDialog(this, row, apps) { vm.loadAll() }.show()
        }
    }

    private fun launchContent(content: TvContent) {
        content.deepLinkUri?.let { uri ->
            try {
                val intent = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
                    .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                startActivity(intent)
                return
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri))
                        .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                    return
                } catch (e2: Exception) { /* fall through */ }
            }
        }
        vm.launchApp(content.packageName)
    }

    // ── Key events ────────────────────────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isIdle) {
            if (event.action == KeyEvent.ACTION_DOWN) exitIdle()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            val focused = currentFocus
            if (focused != null && focused.isRowsDescendant()) {
                lastRowsFocus = java.lang.ref.WeakReference(focused)
                // Row-to-row and row-to-statusbar navigation (only when panels are closed)
                if (!rowsAdapter.allPanelsOpen &&
                    (event.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                     event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
                    if (handleRowNavigation(event.keyCode)) {
                        resetIdleTimer()
                        return true
                    }
                }
            }
            resetIdleTimer()
        }
        return super.dispatchKeyEvent(event)
    }

    /** Intercept vertical d-pad from a card to navigate rows correctly. */
    private fun handleRowNavigation(keyCode: Int): Boolean {
        // Find which child of rvRows currently holds focus
        var currentRowPos = -1
        for (i in 0 until binding.rvRows.childCount) {
            val child = binding.rvRows.getChildAt(i) ?: continue
            if (child.hasFocus()) {
                currentRowPos = binding.rvRows.getChildAdapterPosition(child)
                break
            }
        }
        if (currentRowPos == -1) return false

        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            return if (currentRowPos == 0) {
                binding.btnSettings.requestFocus()
                true
            } else {
                focusFirstCardOfRow(currentRowPos - 1)
            }
        } else {
            return focusFirstCardOfRow(currentRowPos + 1)
        }
    }

    private fun focusFirstCardOfRow(targetPos: Int): Boolean {
        if (targetPos < 0 || targetPos >= rowsAdapter.itemCount) return false
        val lm = binding.rvRows.layoutManager as? LinearLayoutManager ?: return false
        // Scroll to ensure the target row is visible, then focus its first card
        binding.rvRows.scrollToPosition(targetPos)
        binding.rvRows.post {
            val targetView = lm.findViewByPosition(targetPos) ?: return@post
            val rvApps = targetView.findViewById<androidx.recyclerview.widget.RecyclerView>(
                com.shrine.launcher.R.id.rvApps) ?: return@post
            if (rvApps.visibility != View.VISIBLE) return@post
            val innerLm = rvApps.layoutManager as? LinearLayoutManager ?: return@post
            innerLm.scrollToPosition(0)
            rvApps.post {
                val firstItem = innerLm.findViewByPosition(0) ?: return@post
                val card = firstItem.findViewById<View>(com.shrine.launcher.R.id.cardRoot) ?: firstItem
                card.requestFocus()
            }
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (rowsAdapter.allPanelsOpen) {
                rowsAdapter.collapseAllPanels()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ── Package receiver ───────────────────────────────────────────────────────

    private fun registerPackageReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        registerReceiver(packageReceiver, filter)
    }

    override fun onResume() {
        super.onResume()
        initialFocusSet = false
        vm.loadAll()
        vm.scheduleAutoRefresh()
        resetIdleTimer()
    }

    override fun onPause() {
        super.onPause()
        vm.cancelAutoRefresh()
        stopSlideshow()
        idleHandler.removeCallbacks(idleRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        clockTimer?.cancel()
    }
}
