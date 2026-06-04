package com.shrine.launcher.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.shrine.launcher.data.model.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppRepository(private val context: Context) {

    private val pm: PackageManager = context.packageManager

    // SharedPreferences key → epoch ms of last launcher-initiated launch.
    // No system permissions required — we own this data.
    private val launchPrefs by lazy {
        context.getSharedPreferences("shrine_launch_history", Context.MODE_PRIVATE)
    }

    /**
     * Returns all launchable apps sorted alphabetically.
     *
     * Queries LEANBACK_LAUNCHER first (correct for Fire TV), then LAUNCHER.
     * GET_RESOLVED_FILTER (flag 64) returns the IntentFilter alongside each
     * result — needed by PackageManager to correctly resolve TV-only apps.
     * Because we query LEANBACK first and use a map, any package found there
     * is skipped in the LAUNCHER pass, preventing duplicate rows.
     */
    suspend fun getAllApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, AppInfo>()

        for (category in listOf(
            Intent.CATEGORY_LEANBACK_LAUNCHER,  // TV/Fire TV — query first
            Intent.CATEGORY_LAUNCHER            // standard — fill gaps only
        )) {
            val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(category) }
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, PackageManager.GET_RESOLVED_FILTER).forEach { ri ->
                val pkg = ri.activityInfo.packageName
                if (!results.containsKey(pkg)) {           // LEANBACK entries win
                    results[pkg] = AppInfo(
                        packageName = pkg,
                        label       = ri.loadLabel(pm).toString(),
                        icon        = ri.loadIcon(pm),
                        isSystemApp = (ri.activityInfo.applicationInfo.flags and
                                android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    )
                }
            }
        }

        results.values
            .filter { it.packageName != context.packageName } // exclude self
            .sortedBy { it.label.lowercase() }
    }

    /**
     * Returns up to [limit] apps sorted by last launcher-initiated launch time.
     * Uses our own SharedPreferences store — no PACKAGE_USAGE_STATS permission needed.
     */
    suspend fun getRecentlyUsed(limit: Int = 20): List<AppInfo> = withContext(Dispatchers.IO) {
        val allApps = getAllApps().associateBy { it.packageName }
        @Suppress("UNCHECKED_CAST")
        (launchPrefs.all as Map<String, Long>)
            .asSequence()
            .filter { (pkg, _) -> allApps.containsKey(pkg) }
            .sortedByDescending { (_, ts) -> ts }
            .take(limit)
            .mapNotNull { (pkg, _) -> allApps[pkg] }
            .toList()
    }

    /** Record a launcher-initiated launch. Called from HomeActivity after startActivity succeeds. */
    fun recordLaunch(packageName: String) {
        launchPrefs.edit().putLong(packageName, System.currentTimeMillis()).apply()
    }

    /**
     * Launch an app by package name.
     * Tries getLeanbackLaunchIntentForPackage first (correct for Fire TV apps that
     * only register LEANBACK_LAUNCHER), falls back to getLaunchIntentForPackage.
     */
    fun launchApp(packageName: String): Boolean {
        val intent = (pm.getLeanbackLaunchIntentForPackage(packageName)
            ?: pm.getLaunchIntentForPackage(packageName))
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            ?: return false
        return try {
            context.startActivity(intent)
            recordLaunch(packageName)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Get a single AppInfo. */
    fun getAppInfo(packageName: String): AppInfo? {
        return try {
            val ai = pm.getApplicationInfo(packageName, 0)
            AppInfo(
                packageName = packageName,
                label       = pm.getApplicationLabel(ai).toString(),
                icon        = pm.getApplicationIcon(packageName),
                isSystemApp = (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            )
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    companion object {
        @Volatile private var instance: AppRepository? = null
        fun getInstance(context: Context): AppRepository =
            instance ?: synchronized(this) {
                instance ?: AppRepository(context.applicationContext).also { instance = it }
            }
    }
}
