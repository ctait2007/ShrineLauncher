package com.shrine.launcher.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.shrine.launcher.data.model.*
import com.shrine.launcher.data.repository.AppRepository
import com.shrine.launcher.data.repository.PreferencesRepository
import com.shrine.launcher.data.repository.TvContentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val appRepo  = AppRepository.getInstance(application)
    private val prefRepo = PreferencesRepository.getInstance(application)
    private val tvRepo   = TvContentRepository.getInstance(application)

    private val _allApps          = MutableLiveData<List<AppInfo>>()
    val allApps: LiveData<List<AppInfo>> = _allApps

    private val _recentApps       = MutableLiveData<List<AppInfo>>()
    val recentApps: LiveData<List<AppInfo>> = _recentApps

    private val _rows             = MutableLiveData<List<LauncherRow>>()
    val rows: LiveData<List<LauncherRow>> = _rows

    private val _widgets          = MutableLiveData<List<PinnedWidget>>()
    val widgets: LiveData<List<PinnedWidget>> = _widgets

    private val _continueWatching = MutableLiveData<List<TvContent>>()
    val continueWatching: LiveData<List<TvContent>> = _continueWatching

    private val _watchNext        = MutableLiveData<List<TvContent>>()
    val watchNext: LiveData<List<TvContent>> = _watchNext

    private val _newForYou        = MutableLiveData<List<TvContent>>()
    val newForYou: LiveData<List<TvContent>> = _newForYou

    // Map of rowId -> content for individual TvProvider channel rows
    private val _tvProviderContent = MutableLiveData<Map<String, List<TvContent>>>()
    val tvProviderContent: LiveData<Map<String, List<TvContent>>> = _tvProviderContent

    private val _theme            = MutableLiveData<LauncherTheme>()
    val theme: LiveData<LauncherTheme> = _theme

    private val _prefs            = MutableLiveData<LauncherPrefs>()
    val prefs: LiveData<LauncherPrefs> = _prefs

    private val _favourites       = MutableLiveData<List<String>>()
    val favourites: LiveData<List<String>> = _favourites

    init { loadAll() }

    private var refreshJob: kotlinx.coroutines.Job? = null

    fun scheduleAutoRefresh() {
        refreshJob?.cancel()
        val rows = prefRepo.loadRows()
        val minInterval = rows
            .filter { it.autoRefreshMinutes > 0 }
            .minOfOrNull { it.autoRefreshMinutes } ?: return

        refreshJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                delay(minInterval * 60_000L)
                loadAll()
            }
        }
    }

    fun cancelAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = null
    }

    fun loadAll() {
        viewModelScope.launch {
            val p          = withContext(Dispatchers.IO) { prefRepo.loadPrefs() }
            val allApps    = withContext(Dispatchers.IO) { appRepo.getAllApps() }
            val recentApps = withContext(Dispatchers.IO) { appRepo.getRecentlyUsed() }
            val favs       = withContext(Dispatchers.IO) { prefRepo.loadFavourites() }
            val rows       = p.rows

            val cwRow = rows.find { it.channelType == ChannelType.CONTINUE_WATCHING }
            val wnRow = rows.find { it.channelType == ChannelType.WATCH_NEXT }

            val cwDismissed = prefRepo.getDismissedForChannel(cwRow?.id ?: "row_continue_watching")
            val wnDismissed = prefRepo.getDismissedForChannel(wnRow?.id ?: "row_watch_next")

            val cw = tvRepo.getWatchNextPrograms(
                ChannelType.CONTINUE_WATCHING, cwRow?.allowedPackages ?: emptyList()
            ).filter { it.id !in cwDismissed }

            val wn = tvRepo.getWatchNextPrograms(
                ChannelType.WATCH_NEXT, wnRow?.allowedPackages ?: emptyList()
            ).filter { it.id !in wnDismissed }

            val nfy = tvRepo.getPreviewPrograms()

            // Load individual TvProvider channel rows
            val tvProviderRows = rows.filter { it.channelType == ChannelType.TV_PROVIDER && it.isVisible }
            val tvProviderMap  = mutableMapOf<String, List<TvContent>>()
            for (row in tvProviderRows) {
                val channelId = row.tvProviderChannelId ?: continue
                val dismissed = prefRepo.getDismissedForChannel(row.id)
                val content   = tvRepo.getContentForTvProviderChannel(channelId)
                    .filter { it.id !in dismissed }
                tvProviderMap[row.id] = content
            }

            // If a package was just installed, float it to position 0
            val orderedApps = recentlyInstalledPackage?.let { pkg ->
                recentlyInstalledPackage = null
                val newApp = allApps.find { it.packageName == pkg }
                if (newApp != null) listOf(newApp) + allApps.filter { it.packageName != pkg }
                else allApps
            } ?: allApps

            _prefs.value            = p
            _rows.value             = rows.filter { it.isVisible }
            _widgets.value          = p.pinnedWidgets.sortedBy { it.position }
            _theme.value            = p.theme
            _favourites.value       = favs
            _allApps.value          = orderedApps
            _recentApps.value       = recentApps
            _continueWatching.value = cw
            _watchNext.value        = wn
            _newForYou.value        = nfy
            _tvProviderContent.value = tvProviderMap
        }
    }

    fun launchApp(packageName: String) = appRepo.launchApp(packageName)

    fun toggleFavourite(packageName: String) {
        prefRepo.toggleFavourite(packageName)
        _favourites.value = prefRepo.loadFavourites()
    }

    fun dismissChannelContent(content: TvContent) {
        // Find which row this content belongs to and dismiss per-channel
        val rows = _rows.value ?: emptyList()
        val rowId = when (content.channelType) {
            ChannelType.CONTINUE_WATCHING ->
                rows.find { it.channelType == ChannelType.CONTINUE_WATCHING }?.id
                    ?: "row_continue_watching"
            ChannelType.WATCH_NEXT ->
                rows.find { it.channelType == ChannelType.WATCH_NEXT }?.id
                    ?: "row_watch_next"
            ChannelType.TV_PROVIDER ->
                rows.find { it.channelType == ChannelType.TV_PROVIDER &&
                    (_tvProviderContent.value?.get(it.id)?.any { c -> c.id == content.id } == true)
                }?.id ?: return
            else -> return
        }
        prefRepo.addDismissedForChannel(rowId, content.id, content.title)
        _continueWatching.value = _continueWatching.value?.filter { it.id != content.id }
        _watchNext.value        = _watchNext.value?.filter { it.id != content.id }
        _newForYou.value        = _newForYou.value?.filter { it.id != content.id }
        val current = _tvProviderContent.value?.toMutableMap() ?: return
        current.replaceAll { _, list -> list.filter { it.id != content.id } }
        _tvProviderContent.value = current
    }

    fun removeContinueWatching(id: String) {
        prefRepo.removeContinueWatchingEntry(id)
        _continueWatching.value = prefRepo.loadContinueWatching()
    }

    fun getTvContentForRow(row: LauncherRow): List<TvContent> = when (row.channelType) {
        ChannelType.CONTINUE_WATCHING -> _continueWatching.value ?: emptyList()
        ChannelType.WATCH_NEXT        -> _watchNext.value ?: emptyList()
        ChannelType.NEW_FOR_YOU       -> _newForYou.value ?: emptyList()
        ChannelType.TV_PROVIDER       -> _tvProviderContent.value?.get(row.id) ?: emptyList()
        else                          -> emptyList()
    }

    // Virtual AppInfo used as the single icon in the Install row
    private val installAppInfo by lazy {
        val icon = try {
            androidx.core.content.ContextCompat.getDrawable(getApplication(), com.shrine.launcher.R.drawable.ic_install)
        } catch (e: Exception) { null }
        AppInfo(
            packageName = "com.shrine.launcher.INSTALL_ROW",
            label       = "App Installer",
            icon        = icon
        )
    }

    var recentlyInstalledPackage: String? = null

    fun getAppsForRow(row: LauncherRow): List<AppInfo> {
        if (row.kind != RowKind.CATEGORY) return emptyList()
        return when (row.categoryType) {
            CategoryType.ALL_APPS        -> _allApps.value ?: emptyList()
            CategoryType.RECENTLY_OPENED -> _recentApps.value ?: emptyList()
            CategoryType.INSTALL         -> listOf(installAppInfo)
            CategoryType.FAVORITES       -> {
                val favs = _favourites.value ?: emptyList()
                (_allApps.value ?: emptyList())
                    .filter { it.packageName in favs }
                    .sortedBy { favs.indexOf(it.packageName) }
            }
            CategoryType.CUSTOM -> {
                val all = (_allApps.value ?: emptyList()).associateBy { it.packageName }
                row.apps.mapNotNull { all[it] }
            }
            null -> emptyList()
        }
    }

    fun updateRowDisplayMode(rowId: String, mode: CardDisplayMode) {
        val rows = prefRepo.loadRows().map {
            if (it.id == rowId) it.copy(cardDisplayMode = mode) else it
        }
        prefRepo.saveRows(rows)
        _rows.value = rows.filter { it.isVisible }
    }

    fun updateRow(updated: LauncherRow) {
        val rows = prefRepo.loadRows().map { if (it.id == updated.id) updated else it }
        prefRepo.saveRows(rows)
        _rows.value = rows.filter { it.isVisible }
    }

    fun reorderRows(newOrder: List<LauncherRow>) {
        viewModelScope.launch(Dispatchers.IO) {
            prefRepo.reorderRows(newOrder)
        }
    }
}
