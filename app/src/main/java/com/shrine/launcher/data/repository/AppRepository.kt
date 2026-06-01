package com.shrine.launcher.data.repository

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.shrine.launcher.data.model.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppRepository(private val context: Context) {

    private val pm: PackageManager = context.packageManager

    /** Returns all launchable apps sorted alphabetically.
     *  Queries both CATEGORY_LAUNCHER (phone/standard) and
     *  CATEGORY_LEANBACK_LAUNCHER (TV/Fire TV specific) to catch all apps. */
    suspend fun getAllApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, AppInfo>()

        // Standard launcher apps
        val standardIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        // TV/Fire TV leanback launcher apps
        val leanbackIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        }

        for (intent in listOf(standardIntent, leanbackIntent)) {
            pm.queryIntentActivities(intent, 0).forEach { ri ->
                val pkg = ri.activityInfo.packageName
                if (!results.containsKey(pkg)) {
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

    /** Returns apps sorted by last-used time (requires PACKAGE_USAGE_STATS permission). */
    suspend fun getRecentlyUsed(limit: Int = 20): List<AppInfo> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return@withContext emptyList()

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return@withContext emptyList()

        val now = System.currentTimeMillis()
        val stats = usm.queryUsageStats(
            UsageStatsManager.INTERVAL_WEEKLY,
            now - 7 * 24 * 60 * 60 * 1000L,
            now
        ) ?: return@withContext emptyList()

        val allApps = getAllApps().associateBy { it.packageName }

        stats.asSequence()
            .filter { it.lastTimeUsed > 0 && allApps.containsKey(it.packageName) }
            .sortedByDescending { it.lastTimeUsed }
            .take(limit)
            .mapNotNull { allApps[it.packageName] }
            .toList()
    }

    /** Launch an app by package name. Returns false if not found. */
    fun launchApp(packageName: String): Boolean {
        val intent = pm.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
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
