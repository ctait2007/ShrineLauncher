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
import kotlinx.coroutines.delay

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val appRepo  = AppRepository.getInstance(application)
    private val prefRepo = PreferencesRepository.getInstance(application)
    private val tvRepo   = TvContentRepository.getInstance(application)

    private val _allApps       = MutableLiveData<List<AppInfo>>()
    val allApps: LiveData<List<AppInfo>> = _allApps

    private val _recentApps    = MutableLiveData<List<AppInfo>>()
    val recentApps: LiveData<List<AppInfo>> = _recentApps

    private val _rows          = MutableLiveData<List<LauncherRow>>()
    val rows: LiveData<List<LauncherRow>> = _rows

    private val _widgets       = MutableLiveData<List<PinnedWidget>>()
    val widgets: LiveData<List<PinnedWidget>> = _widgets

    private val _continueWatching = MutableLiveData<List<TvContent>>()
    val continueWatching: LiveData<List<TvContent>> = _continueWatching

    private val _watchNext     = MutableLiveData<List<TvContent>>()
    val watchNext: LiveData<List<TvContent>> = _watchNext

    private val _newForYou     = MutableLiveData<List<TvContent>>()
    val newForYou: LiveData<List<TvContent>> = _newForYou

    private val _theme         = MutableLiveData<LauncherTheme>()
    val theme: LiveData<LauncherTheme> = _theme

    private val _prefs         = MutableLiveData<LauncherPrefs>()
    val prefs: LiveData<LauncherPrefs> = _prefs

    private val _favourites    = MutableLiveData<List<String>>()
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
                kotlinx.coroutines.delay(minInterval * 60_000L)
                android.util.Log.d("HomeViewModel", "Auto-refresh fired (${minInterval}min interval)")
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
            _allApps.value    = appRepo.getAllApps()
            _recentApps.value = appRepo.getRecentlyUsed()
            val rows = prefRepo.loadRows()
            val cwRow = rows.find { it.channelType == ChannelType.CONTINUE_WATCHING }
            val wnRow = rows.find { it.channelType == ChannelType.WATCH_NEXT }
            _continueWatching.value = tvRepo.getWatchNextPrograms(
                ChannelType.CONTINUE_WATCHING, cwRow?.allowedPackages ?: emptyList())
            _watchNext.value = tvRepo.getWatchNextPrograms(
                ChannelType.WATCH_NEXT, wnRow?.allowedPackages ?: emptyList())
            _newForYou.value = tvRepo.getPreviewPrograms()
        }
        val p = prefRepo.loadPrefs()
        _prefs.value    = p
        _rows.value     = p.rows.filter { it.isVisible }
        _widgets.value  = p.pinnedWidgets.sortedBy { it.position }
        _theme.value    = p.theme
        _favourites.value = prefRepo.loadFavourites()
    }

    fun launchApp(packageName: String) = appRepo.launchApp(packageName)

    fun toggleFavourite(packageName: String) {
        prefRepo.toggleFavourite(packageName)
        _favourites.value = prefRepo.loadFavourites()
    }

    fun dismissChannelContent(content: com.shrine.launcher.data.model.TvContent) {
        // Remove from local live data immediately (optimistic update)
        // TvProvider deletion requires signature permission we don't have
        _continueWatching.value = _continueWatching.value?.filter { it.id != content.id }
        _watchNext.value        = _watchNext.value?.filter { it.id != content.id }
        _newForYou.value        = _newForYou.value?.filter { it.id != content.id }
    }

    fun removeContinueWatching(id: String) {
        prefRepo.removeContinueWatchingEntry(id)
        _continueWatching.value = prefRepo.loadContinueWatching()
    }

    fun getTvContentForRow(row: LauncherRow): List<TvContent> = when (row.channelType) {
        ChannelType.CONTINUE_WATCHING -> _continueWatching.value ?: emptyList()
        ChannelType.WATCH_NEXT        -> _watchNext.value ?: emptyList()
        ChannelType.NEW_FOR_YOU       -> _newForYou.value ?: emptyList()
        else                          -> emptyList()
    }

    fun getAppsForRow(row: LauncherRow): List<AppInfo> {
        if (row.kind != RowKind.CATEGORY) return emptyList()
        return when (row.categoryType) {
            CategoryType.ALL_APPS        -> _allApps.value ?: emptyList()
            CategoryType.RECENTLY_OPENED -> _recentApps.value ?: emptyList()
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
            CategoryType.SUGGESTIONS -> (_allApps.value ?: emptyList()).take(12)
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
}
