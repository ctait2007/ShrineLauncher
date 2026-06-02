package com.shrine.launcher.ui.home

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
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

    private val tvObserver = TvDatabaseObserver { vm.loadAll() }

    private val tvPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        TvPermissionUtil.checkAndRequestAccess(this)
        vm.loadAll()
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) { vm.loadAll() }
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
        rowsAdapter = RowsAdapter(
            onAppClick             = { app -> handleAppClick(app) },
            onAppLongClick         = { app -> showAppContextMenu(app) },
            onContentClick         = { content -> launchContent(content) },
            onContentLongClick     = { content -> showContentContextMenu(content) },
            onRowFocused           = { _ -> },
            onRowSettingsClick     = { row -> showRowSettingsDialog(row) },
            onRowDisplayModeToggle = { row ->
                vm.updateRowDisplayMode(row.id,
                    if (row.cardDisplayMode == CardDisplayMode.ICON)
                        CardDisplayMode.BANNER else CardDisplayMode.ICON)
            },
            onRowIconSizeChange    = { row -> vm.updateRow(row) },
            cornerRadiusPercent    = prefs?.cardCornerRadiusPercent ?: 50,
            iconSizeDp             = iconSizeDp(prefs?.iconSizeLabel ?: "M"),
            rowStartPaddingDp      = ((prefs?.rowStartPaddingDp ?: 20) * 120 / 100),
            itemSpacingDp          = prefs?.itemSpacingPercent ?: 10,
            rowSpacingDp           = prefs?.rowSpacingPercent ?: 20
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
                    binding.root.setBackgroundColor(android.graphics.Color.parseColor("#0D0D0D"))
                } else if (allUris.size == 1) {
                    applyWallpaper(allUris[0])
                } else {
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
        for (i in list.indices) {
            val item = list[i]
            if (item !is RowItem.CategoryRow || item.apps.isEmpty()) continue
            val rowView = lm.findViewByPosition(i) ?: continue
            val rvApps  = rowView.findViewById<androidx.recyclerview.widget.RecyclerView>(appsRvId)
                ?: continue
            if (rvApps.visibility != View.VISIBLE) continue
            val innerLm = rvApps.layoutManager as? LinearLayoutManager ?: continue
            val firstItem = innerLm.findViewByPosition(0) ?: continue
            val card = firstItem.findViewById<View>(cardRootId) ?: firstItem
            card.requestFocus()
            return
        }
        initialFocusSet = false
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
            onDismissed       = { activePanel = null; vm.loadAll(); binding.btnSettings.post { binding.btnSettings.requestFocus() } },
            initialApp        = initialApp,
            onSettingsChanged = { vm.loadAll() }
        )
        activePanel = dialog
        dialog.show()
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
            return true  // consume the event — don't propagate
        }
        if (event.action == KeyEvent.ACTION_DOWN) resetIdleTimer()
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return true
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
