package com.shrine.launcher.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.shrine.launcher.data.model.*
import java.util.UUID

class PreferencesRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("shrine_launcher_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    init {
        // v0.10.0 migration: wipe flat dismissed_content set (now per-channel)
        if (prefs.contains("dismissed_content")) {
            prefs.edit().remove("dismissed_content").apply()
        }
    }

    fun loadPrefs(): LauncherPrefs {
        val json = prefs.getString(KEY_PREFS, null) ?: return LauncherPrefs()
        val loaded = try { gson.fromJson(json, LauncherPrefs::class.java) ?: LauncherPrefs() }
                     catch (e: Exception) { LauncherPrefs() }
        val fixedRows = loaded.rows.map { row ->
            when (row.id) {
                "row_continue_watching" ->
                    if (row.channelType != ChannelType.CONTINUE_WATCHING)
                        row.copy(channelType = ChannelType.CONTINUE_WATCHING)
                    else row
                "row_watch_next" ->
                    if (row.channelType != ChannelType.WATCH_NEXT)
                        row.copy(channelType = ChannelType.WATCH_NEXT)
                    else row
                else -> row
            }
        }
        // Ensure built-in rows exist
        val ensured = ensureBuiltInRows(fixedRows)
        return if (ensured == loaded.rows) loaded else loaded.copy(rows = ensured)
    }

    private fun ensureBuiltInRows(rows: List<LauncherRow>): List<LauncherRow> {
        val result = rows.toMutableList()
        if (result.none { it.id == "row_favourites" }) {
            result.add(LauncherRow(
                id = "row_favourites", title = "Favourites",
                kind = RowKind.CATEGORY, categoryType = CategoryType.FAVORITES
            ))
        }
        if (result.none { it.id == "row_install" }) {
            result.add(LauncherRow(
                id = "row_install", title = "Install",
                kind = RowKind.CATEGORY, categoryType = CategoryType.INSTALL
            ))
        }
        return result
    }

    fun savePrefs(p: LauncherPrefs) {
        prefs.edit().putString(KEY_PREFS, gson.toJson(p)).apply()
    }

    fun loadRows(): MutableList<LauncherRow> = loadPrefs().rows.toMutableList()
    fun saveRows(rows: List<LauncherRow>) = savePrefs(loadPrefs().copy(rows = rows))
    fun addRow(row: LauncherRow) { val r = loadRows(); r.add(row); saveRows(r) }
    fun removeRow(rowId: String) = saveRows(loadRows().filter { it.id != rowId })
    fun updateRow(updated: LauncherRow) =
        saveRows(loadRows().map { if (it.id == updated.id) updated else it })
    fun reorderRows(newOrder: List<LauncherRow>) = saveRows(newOrder)

    fun loadWidgets(): MutableList<PinnedWidget> = loadPrefs().pinnedWidgets.toMutableList()
    fun saveWidgets(widgets: List<PinnedWidget>) = savePrefs(loadPrefs().copy(pinnedWidgets = widgets))
    fun addWidget(type: WidgetType, title: String) {
        val w = loadWidgets()
        w.add(PinnedWidget(UUID.randomUUID().toString(), type, title, w.size))
        saveWidgets(w)
    }
    fun removeWidget(widgetId: String) = saveWidgets(loadWidgets().filter { it.id != widgetId })

    fun loadTheme(): LauncherTheme = loadPrefs().theme
    fun saveTheme(theme: LauncherTheme) = savePrefs(loadPrefs().copy(theme = theme))

    fun loadContinueWatching(): List<TvContent> {
        val json = prefs.getString(KEY_CONTINUE_WATCHING, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<TvContent>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    fun saveContinueWatching(entries: List<TvContent>) {
        prefs.edit().putString(KEY_CONTINUE_WATCHING, gson.toJson(entries)).apply()
    }

    fun upsertContinueWatchingEntry(entry: TvContent) {
        val list = loadContinueWatching().toMutableList()
        val idx = list.indexOfFirst { it.id == entry.id }
        if (idx >= 0) list[idx] = entry else list.add(0, entry)
        saveContinueWatching(list.take(20))
    }

    fun removeContinueWatchingEntry(id: String) =
        saveContinueWatching(loadContinueWatching().filter { it.id != id })

    fun loadFavourites(): MutableList<String> {
        val json = prefs.getString(KEY_FAVOURITES, null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<String>>() {}.type
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) { mutableListOf() }
    }

    fun saveFavourites(packageNames: List<String>) {
        prefs.edit().putString(KEY_FAVOURITES, gson.toJson(packageNames)).apply()
    }

    fun toggleFavourite(packageName: String): Boolean {
        val favs = loadFavourites()
        return if (favs.contains(packageName)) {
            favs.remove(packageName); saveFavourites(favs); false
        } else {
            favs.add(packageName); saveFavourites(favs); true
        }
    }

    // ── Per-channel dismissed content (v0.10.0+) ─────────────────────────────

    fun addDismissedForChannel(channelId: String, contentId: String, contentTitle: String = contentId) {
        val key = dismissedKey(channelId)
        val current = prefs.getStringSet(key, emptySet())!!.toMutableSet()
        // Remove any existing entry for this contentId before re-adding with title
        current.removeAll { it == contentId || it.startsWith("$contentId|||") }
        current.add("$contentId|||$contentTitle")
        prefs.edit().putStringSet(key, current).apply()
    }

    fun getDismissedForChannel(channelId: String): Set<String> =
        prefs.getStringSet(dismissedKey(channelId), emptySet()) ?: emptySet()

    /** Returns (contentId, displayTitle) pairs for the dismissed items. */
    fun getDismissedWithTitles(channelId: String): List<Pair<String, String>> =
        getDismissedForChannel(channelId).map { entry ->
            val parts = entry.split("|||", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else entry to entry
        }

    fun restoreDismissedForChannel(channelId: String, contentId: String) {
        val key = dismissedKey(channelId)
        val current = prefs.getStringSet(key, emptySet())!!.toMutableSet()
        current.removeAll { it == contentId || it.startsWith("$contentId|||") }
        prefs.edit().putStringSet(key, current).apply()
    }

    fun getDismissedIdsForRow(row: com.shrine.launcher.data.model.LauncherRow): Set<String> =
        getDismissedForChannel(row.id)

    // Legacy shim — some call sites use this; routes to a global bucket that is always empty post-migration
    fun getDismissedIds(): Set<String> = emptySet()
    fun addDismissedContent(id: String) { /* no-op post-migration — callers should use addDismissedForChannel */ }

    private fun dismissedKey(channelId: String) = "dismissed_ch_$channelId"

    companion object {
        private const val KEY_PREFS             = "launcher_prefs"
        private const val KEY_CONTINUE_WATCHING = "continue_watching"
        private const val KEY_FAVOURITES        = "favourites"

        @Volatile private var instance: PreferencesRepository? = null
        fun getInstance(context: Context): PreferencesRepository =
            instance ?: synchronized(this) {
                instance ?: PreferencesRepository(context.applicationContext).also { instance = it }
            }
    }
}
