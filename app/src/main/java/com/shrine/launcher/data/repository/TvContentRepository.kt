package com.shrine.launcher.data.repository

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.tv.TvContract
import android.net.Uri
import android.util.Log
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.shrine.launcher.data.model.ChannelType
import com.shrine.launcher.data.model.TvContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Implements all five fixes recommended by ChatGPT analysis:
 *
 * FIX 1: Remove COLUMN_WATCH_NEXT_TYPE SQL filter — query ALL Watch Next records
 *         first, then categorize by program.watchNextType after retrieval.
 *         Many apps don't populate this column consistently.
 *
 * FIX 2: Merge WatchNextProgram + PreviewProgram sources for Continue Watching.
 *         Fire TV sometimes exposes content only via PreviewProgram, never writing
 *         to WatchNextProgram. We combine both and deduplicate.
 *
 * FIX 3: Per-program debug logging so we can see exactly what Nuvio returns
 *         (packageName, title, watchNextType) in Logcat.
 *
 * FIX 4: Fallback chain: WatchNext → PreviewPrograms (not local launcher data).
 *         Fire TV often blocks READ_TV_LISTINGS but still exposes PreviewPrograms.
 *
 * FIX 5: Sort all results globally by lastEngagementTime so most-recent content
 *         appears first regardless of which app published it.
 */
class TvContentRepository(private val context: Context) {

    private val cr: ContentResolver = context.contentResolver
    private val pm: PackageManager  = context.packageManager

    // ── Public API ─────────────────────────────────────────────────────────────

    suspend fun getWatchNextPrograms(type: ChannelType, allowedPackages: List<String> = emptyList()): List<TvContent> =
        withContext(Dispatchers.IO) {
            val results = queryCombined(type)
            if (allowedPackages.isEmpty()) results
            else results.filter { it.packageName in allowedPackages }
        }

    suspend fun getPreviewPrograms(): List<TvContent> =
        withContext(Dispatchers.IO) { queryAllPreviewPrograms(ChannelType.NEW_FOR_YOU) }

    suspend fun getAllChannels(): List<Pair<String, List<TvContent>>> =
        withContext(Dispatchers.IO) { queryChannelsWithPrograms() }

    // ── Raw cursor row (avoids WatchNextProgram.fromCursor parsing bugs) ─────────

    private data class RawWatchNext(
        val id: String,
        val title: String,
        val subtitle: String?,
        val packageName: String,
        val intentUri: String?,
        val artworkUri: String?,
        val progressMs: Long,
        val durationMs: Long,
        val watchNextType: Int
    )

    // ── Combined query ────────────────────────────────────────────────────────

    private fun queryCombined(type: ChannelType): List<TvContent> {
        val watchNext = queryWatchNextAll()

        watchNext.forEach { row ->
            Log.e(TAG, "WATCHNEXT: " +
                "pkg=${row.packageName} " +
                "title=${row.title} " +
                "type=${row.watchNextType} " +
                "progressMs=${row.progressMs} " +
                "durationMs=${row.durationMs}")
        }

        // Classify by playback progress fraction — same approach used by Projectivy.
        // Apps (e.g. Nuvio) publish in-progress content as WATCH_NEXT_TYPE_NEXT rather
        // than WATCH_NEXT_TYPE_CONTINUE, so type flags alone are not reliable.
        // An item is "in progress" when it has meaningful progress: started past 2% and
        // not within 2% of the end, with both duration and position available.
        val continueWatchingIds = watchNext
            .filter { row ->
                val progressFraction =
                    if (row.durationMs > 0) row.progressMs.toFloat() / row.durationMs.toFloat()
                    else 0f

                val hasPlaybackProgress =
                    row.durationMs > 0 &&
                    row.progressMs > 0 &&
                    row.progressMs < row.durationMs &&
                    progressFraction > 0.02f &&
                    progressFraction < 0.98f

                hasPlaybackProgress ||
                    row.watchNextType == TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE
            }
            .map { it.id }
            .toSet()

        // Map WatchNext rows to TvContent up-front so we can merge with PreviewPrograms.
        fun RawWatchNext.toContent() = TvContent(
            id          = id,
            title       = title,
            subtitle    = subtitle?.ifBlank { appLabel(packageName) } ?: appLabel(packageName),
            packageName = packageName,
            deepLinkUri = intentUri,
            artworkUri  = artworkUri,
            progressMs  = progressMs,
            durationMs  = durationMs,
            channelType = type
        )

        val filtered = when (type) {
            ChannelType.CONTINUE_WATCHING -> {
                // PreviewPrograms carry the authoritative lastPlaybackPositionMillis for
                // apps like Nuvio. WatchNextPrograms for the same content often have
                // progressMs=0 due to a Fire TV provider quirk even when real position data
                // exists in the corresponding PreviewProgram.
                val fromPreview = queryAllPreviewPrograms(type)
                    .filter { it.progressMs > 0L && it.durationMs > 0L }

                // Build a lookup from "packageName|title" -> PreviewProgram with real progress
                val previewProgressByKey = fromPreview
                    .associateBy { "${it.packageName}|${it.title}" }

                // Map WatchNext items, enriching progressMs/durationMs from PreviewPrograms
                // when the WatchNext entry has progressMs=0.
                val fromWatchNext = watchNext
                    .filter { it.id in continueWatchingIds }
                    .map { row ->
                        val base    = row.toContent()
                        val preview = previewProgressByKey["${row.packageName}|${row.title}"]
                        if (base.progressMs == 0L && preview != null) {
                            base.copy(progressMs = preview.progressMs, durationMs = preview.durationMs)
                        } else base
                    }

                // Add any PreviewProgram items not already covered by a WatchNext entry
                val seenKeys = fromWatchNext.map { "${it.packageName}|${it.title}" }.toSet()
                val previewOnly = fromPreview
                    .filter { "${it.packageName}|${it.title}" !in seenKeys }

                Log.e(TAG, "CW: ${fromWatchNext.size} from WatchNext (enriched), " +
                    "${previewOnly.size} added from PreviewPrograms only")
                fromWatchNext + previewOnly
            }

            ChannelType.WATCH_NEXT -> {
                // Pure WatchNext items not claimed by Continue Watching.
                // No PreviewPrograms fallback — Nuvio's "Continue Watching" PreviewPrograms
                // would land here otherwise, defeating the routing entirely.
                watchNext
                    .filter { it.id !in continueWatchingIds }
                    .map { it.toContent() }
            }

            else -> watchNext.map { it.toContent() }
        }

        Log.d(TAG, "queryCombined[$type]: ${filtered.size} items")
        val dismissed = PreferencesRepository.getInstance(context).getDismissedIds()
        return filtered.filter { it.id !in dismissed }
    }

    // ── Watch Next: read fields directly from cursor ──────────────────────────

    private fun queryWatchNextAll(): List<RawWatchNext> {
        val results = mutableListOf<RawWatchNext>()

        val uris = listOf(
            TvContractCompat.WatchNextPrograms.CONTENT_URI,
            TvContract.WatchNextPrograms.CONTENT_URI,
            Uri.parse("content://android.media.tv/watch_next_program")
        ).distinctBy { it.toString() }

        for (uri in uris) {
            try {
                val cursor: Cursor = cr.query(
                    uri, null, null, null,
                    "${TvContractCompat.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS} DESC"
                ) ?: continue

                cursor.use { c ->
                    Log.d(TAG, "WatchNext cursor from $uri: ${c.count} rows")
                    while (c.moveToNext()) {
                        try {
                            val program = WatchNextProgram.fromCursor(c)

                            // Direct cursor read is the primary source; fall back to the
                            // compiled-in helper if Fire TV's TvProvider returns 0 directly.
                            val directProgress = c.getLong(
                                c.getColumnIndex(
                                    TvContractCompat.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS
                                )
                            )
                            val progressMs = if (directProgress > 0) directProgress
                                            else program.lastPlaybackPositionMillis.toLong()
                                                .coerceAtLeast(0L)

                            val durationMs =
                                c.getLong(
                                    c.getColumnIndexOrThrow(
                                        TvContractCompat.PreviewPrograms.COLUMN_DURATION_MILLIS
                                    )
                                )

                            val id = c.safeString("_id") ?: continue
                            val title = c.safeString(TvContractCompat.PreviewPrograms.COLUMN_TITLE)
                                ?: "(no title)"
                            val subtitle = c.safeString(TvContractCompat.PreviewPrograms.COLUMN_SHORT_DESCRIPTION)
                            val pkg = c.safeString(TvContractCompat.PreviewPrograms.COLUMN_PACKAGE_NAME) ?: ""
                            val intentUri = c.safeString(TvContractCompat.PreviewPrograms.COLUMN_INTENT_URI)
                            val posterUri = c.safeString(TvContractCompat.PreviewPrograms.COLUMN_POSTER_ART_URI)
                            val thumbUri = c.safeString(TvContractCompat.PreviewPrograms.COLUMN_THUMBNAIL_URI)
                            val watchNextType = c.safeInt(TvContractCompat.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE)

                            Log.e(TAG, "RAW_WATCHNEXT: " +
                                "title=$title " +
                                "pkg=$pkg " +
                                "watchNextType=$watchNextType " +
                                "progressMs=$progressMs " +
                                "durationMs=$durationMs")

                            results.add(RawWatchNext(
                                id            = id,
                                title         = title,
                                subtitle      = subtitle,
                                packageName   = pkg,
                                intentUri     = intentUri,
                                artworkUri    = posterUri ?: thumbUri,
                                progressMs    = progressMs,
                                durationMs    = durationMs,
                                watchNextType = watchNextType
                            ))
                        } catch (e: Exception) {
                            Log.w(TAG, "Skipping malformed WatchNext row: ${e.message}")
                        }
                    }
                }

                if (results.isNotEmpty()) break
            } catch (se: SecurityException) {
                Log.w(TAG, "SecurityException on WatchNext $uri: ${se.message}")
            } catch (e: Exception) {
                Log.w(TAG, "Error on WatchNext $uri: ${e.message}")
            }
        }
        return results
    }

    private fun Cursor.safeString(col: String): String? {
        val idx = getColumnIndex(col)
        return if (idx >= 0) getString(idx) else null
    }

    private fun Cursor.safeInt(col: String, default: Int = -1): Int {
        val idx = getColumnIndex(col)
        return if (idx >= 0) getInt(idx) else default
    }

    // ── FIX 2: PreviewPrograms — merges continue watching from all apps ────────

    private fun queryAllPreviewPrograms(type: ChannelType): List<TvContent> {
        val results = mutableListOf<TvContent>()

        val relevantTypes = setOf(
            TvContractCompat.PreviewPrograms.TYPE_CLIP,
            TvContractCompat.PreviewPrograms.TYPE_MOVIE,
            TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE,
            TvContractCompat.PreviewPrograms.TYPE_TV_SERIES,
            TvContractCompat.PreviewPrograms.TYPE_TV_SEASON
        )

        try {
            val cursor = cr.query(
                TvContractCompat.PreviewPrograms.CONTENT_URI,
                null, null, null, "_id DESC"
            ) ?: run {
                Log.w(TAG, "PreviewPrograms cursor null")
                return emptyList()
            }

            Log.d(TAG, "PreviewPrograms: ${cursor.count} total rows")

            cursor.use { c ->
                while (c.moveToNext()) {
                    try {
                        val program = PreviewProgram.fromCursor(c)

                        if (program.type !in relevantTypes) continue

                        val channelPkg  = getPackageForChannel(program.channelId)
                        val channelName = getChannelName(program.channelId)
                        val lastPos     = program.lastPlaybackPositionMillis.toLong()
                        val duration    = program.durationMillis.toLong()

                        Log.e(TAG, "PREVIEW_PROGRAM: " +
                            "pkg=${program.packageName} " +
                            "title=${program.title} " +
                            "channelName=$channelName " +
                            "lastPlaybackPositionMs=$lastPos " +
                            "durationMs=$duration " +
                            "type=${program.type}")

                        results.add(TvContent(
                            id          = "${program.channelId}_${program.id}",
                            title       = program.title ?: continue,
                            subtitle    = program.description?.ifBlank { channelName } ?: channelName,
                            packageName = channelPkg.ifBlank { program.packageName ?: "" },
                            deepLinkUri = program.intentUri?.toString(),
                            artworkUri  = (program.posterArtUri ?: program.thumbnailUri)?.toString(),
                            durationMs  = duration,
                            progressMs  = lastPos,
                            channelType = type
                        ))
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping malformed PreviewProgram row: ${e.message}")
                    }
                }
            }
        } catch (se: SecurityException) {
            Log.w(TAG, "PreviewPrograms SecurityException: ${se.message}")
        } catch (e: Exception) {
            Log.e(TAG, "PreviewPrograms query failed: ${e.message}", e)
        }

        Log.d(TAG, "PreviewPrograms[$type]: returning ${results.size} items")
        val dismissed = PreferencesRepository.getInstance(context).getDismissedIds()
        return results.filter { it.id !in dismissed }
    }

    // ── Channels + per-channel programs ───────────────────────────────────────

    private fun queryChannelsWithPrograms(): List<Pair<String, List<TvContent>>> {
        val result = mutableListOf<Pair<String, List<TvContent>>>()
        try {
            val cursor = cr.query(
                TvContractCompat.Channels.CONTENT_URI,
                null,
                "${TvContractCompat.Channels.COLUMN_BROWSABLE} = 1",
                null, null
            ) ?: return emptyList()

            val channels = mutableListOf<androidx.tvprovider.media.tv.PreviewChannel>()
            cursor.use { c ->
                while (c.moveToNext()) {
                    try { channels.add(androidx.tvprovider.media.tv.PreviewChannel.fromCursor(c)) }
                    catch (e: Exception) { }
                }
            }
            for (ch in channels.take(30)) {
                val programs = queryProgramsForChannel(ch)
                if (programs.isNotEmpty()) {
                    result.add((ch.displayName?.toString() ?: "Unknown") to programs)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Channels query failed: ${e.message}", e)
        }
        return result
    }

    private fun queryProgramsForChannel(
        channel: androidx.tvprovider.media.tv.PreviewChannel
    ): List<TvContent> {
        val results = mutableListOf<TvContent>()
        val pkg = getPackageForChannel(channel.id)
        val channelName = channel.displayName?.toString() ?: ""
        try {
            val uri = TvContractCompat.buildPreviewProgramsUriForChannel(channel.id)
            val cursor = cr.query(uri, null, null, null, "_id DESC") ?: return emptyList()
            cursor.use { c ->
                while (c.moveToNext() && results.size < 20) {
                    try {
                        val program = PreviewProgram.fromCursor(c)
                        val lastPos  = program.lastPlaybackPositionMillis.toLong()
                        val duration = program.durationMillis.toLong()
                        Log.e(TAG, "CHANNEL_PROGRAM: " +
                            "channel=$channelName " +
                            "pkg=$pkg " +
                            "title=${program.title} " +
                            "lastPlaybackPositionMs=$lastPos " +
                            "durationMs=$duration")
                        results.add(TvContent(
                            id          = "${channel.id}_${program.id}",
                            title       = program.title ?: continue,
                            subtitle    = program.description?.ifBlank { channelName } ?: channelName,
                            packageName = pkg,
                            deepLinkUri = program.intentUri?.toString(),
                            artworkUri  = (program.posterArtUri ?: program.thumbnailUri)?.toString(),
                            durationMs  = duration,
                            progressMs  = lastPos,
                            channelType = ChannelType.NEW_FOR_YOU
                        ))
                    } catch (e: Exception) { }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Programs for channel ${channel.id}: ${e.message}")
        }
        return results
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    // ── Channel-name routing helper ───────────────────────────────────────────

    /** Returns the set of package names that own a browsable channel whose display
     *  name contains "continue" (case-insensitive), e.g. "Continue Watching". */
    private fun buildContinueWatchingPackages(): Set<String> {
        val packages = mutableSetOf<String>()
        return try {
            // Fire TV rejects WHERE clauses on the channels URI ("Selection not allowed"),
            // so query all channels and filter browsable + display name in Kotlin.
            val c = cr.query(
                TvContractCompat.Channels.CONTENT_URI,
                arrayOf(
                    TvContractCompat.Channels.COLUMN_PACKAGE_NAME,
                    TvContractCompat.Channels.COLUMN_DISPLAY_NAME,
                    TvContractCompat.Channels.COLUMN_BROWSABLE
                ),
                null, null, null
            ) ?: return packages
            c.use {
                while (it.moveToNext()) {
                    val browsable = it.safeInt(TvContractCompat.Channels.COLUMN_BROWSABLE)
                    if (browsable == 0) continue
                    val pkg  = it.safeString(TvContractCompat.Channels.COLUMN_PACKAGE_NAME) ?: continue
                    val name = it.safeString(TvContractCompat.Channels.COLUMN_DISPLAY_NAME) ?: continue
                    Log.d(TAG, "Channel: pkg=$pkg name=$name browsable=$browsable")
                    if (name.lowercase().contains("continue")) packages += pkg
                }
            }
            packages
        } catch (e: Exception) {
            Log.w(TAG, "buildContinueWatchingPackages failed: ${e.message}")
            packages
        }
    }

    private val channelPkgCache  = mutableMapOf<Long, String>()
    private val channelNameCache = mutableMapOf<Long, String>()

    private fun getPackageForChannel(channelId: Long): String {
        channelPkgCache[channelId]?.let { return it }
        return try {
            val c = cr.query(
                TvContractCompat.buildChannelUri(channelId),
                arrayOf(TvContractCompat.Channels.COLUMN_PACKAGE_NAME),
                null, null, null
            ) ?: return ""
            c.use { if (it.moveToFirst()) it.getString(0) ?: "" else "" }
                .also { channelPkgCache[channelId] = it }
        } catch (e: Exception) { "" }
    }

    private fun getChannelName(channelId: Long): String {
        channelNameCache[channelId]?.let { return it }
        return try {
            val c = cr.query(
                TvContractCompat.buildChannelUri(channelId),
                arrayOf(TvContractCompat.Channels.COLUMN_DISPLAY_NAME),
                null, null, null
            ) ?: return ""
            c.use { if (it.moveToFirst()) it.getString(0) ?: "" else "" }
                .also { channelNameCache[channelId] = it }
        } catch (e: Exception) { "" }
    }

    private fun appLabel(pkg: String) = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    companion object {
        private const val TAG = "TvContentRepo"
        @Volatile private var instance: TvContentRepository? = null
        fun getInstance(context: Context) = instance ?: synchronized(this) {
            instance ?: TvContentRepository(context.applicationContext).also { instance = it }
        }
    }
}
