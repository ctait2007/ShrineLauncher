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

    fun loadPrefs(): LauncherPrefs {
        val json = prefs.getString(KEY_PREFS, null) ?: return LauncherPrefs()
        return try { gson.fromJson(json, LauncherPrefs::class.java) ?: LauncherPrefs() }
        catch (e: Exception) { LauncherPrefs() }
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

    fun addDismissedContent(id: String) {
        val current = getDismissedIds().toMutableSet()
        current.add(id)
        prefs.edit().putStringSet("dismissed_content", current).apply()
    }

    fun getDismissedIds(): Set<String> =
        prefs.getStringSet("dismissed_content", emptySet()) ?: emptySet()

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
