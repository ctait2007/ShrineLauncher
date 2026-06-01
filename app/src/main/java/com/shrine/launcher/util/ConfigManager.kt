package com.shrine.launcher.util

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.gson.GsonBuilder
import com.shrine.launcher.data.model.LauncherPrefs
import com.shrine.launcher.data.repository.PreferencesRepository
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

object ConfigManager {

    private const val TAG = "ConfigManager"
    private const val CONFIG_VERSION = 2
    private val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()

    data class ConfigExport(
        val version: Int = CONFIG_VERSION,
        val exportedAt: Long = System.currentTimeMillis(),
        val appVersion: String = "1.0",
        val prefs: LauncherPrefs
    )

    fun exportToUri(context: Context, uri: Uri): Boolean {
        return try {
            val prefs = PreferencesRepository.getInstance(context).loadPrefs()
            val export = ConfigExport(prefs = prefs)
            val json = gson.toJson(export)
            context.contentResolver.openOutputStream(uri)?.use { os ->
                OutputStreamWriter(os, Charsets.UTF_8).use { it.write(json) }
            }
            Log.d(TAG, "Exported: rows=${prefs.rows.size}, " +
                "iconSize=${prefs.iconSizeLabel}, " +
                "cornerRadius=${prefs.cardCornerRadiusPercent}, " +
                "bottomMargin=${prefs.rowsBottomMarginPercent}, " +
                "wallpaper=${prefs.wallpaperUri}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Export failed: ${e.message}", e)
            false
        }
    }

    fun importFromUri(context: Context, uri: Uri): Boolean {
        return try {
            val json = context.contentResolver.openInputStream(uri)?.use { ins ->
                BufferedReader(InputStreamReader(ins, Charsets.UTF_8)).readText()
            } ?: return false

            val export = gson.fromJson(json, ConfigExport::class.java) ?: return false
            val repo = PreferencesRepository.getInstance(context)

            repo.savePrefs(export.prefs)
            export.prefs.theme.let { repo.saveTheme(it) }

            Log.d(TAG, "Imported v${export.version}: rows=${export.prefs.rows.size}, " +
                "iconSize=${export.prefs.iconSizeLabel}, " +
                "cornerRadius=${export.prefs.cardCornerRadiusPercent}, " +
                "bottomMargin=${export.prefs.rowsBottomMarginPercent}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Import failed: ${e.message}", e)
            false
        }
    }

    fun exportToString(context: Context): String? {
        return try {
            val prefs = PreferencesRepository.getInstance(context).loadPrefs()
            gson.toJson(ConfigExport(prefs = prefs))
        } catch (e: Exception) {
            Log.e(TAG, "Export to string failed: ${e.message}", e)
            null
        }
    }

    fun importFromString(context: Context, json: String): Boolean {
        return try {
            val export = gson.fromJson(json, ConfigExport::class.java) ?: return false
            val repo = PreferencesRepository.getInstance(context)
            repo.savePrefs(export.prefs)
            export.prefs.theme.let { repo.saveTheme(it) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Import from string failed: ${e.message}", e)
            false
        }
    }
}
