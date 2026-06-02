package com.shrine.launcher.data.model

import android.graphics.drawable.Drawable

// ── App Info ──────────────────────────────────────────────────────────────────

data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val isSystemApp: Boolean = false
)

// ── Display mode for app cards ────────────────────────────────────────────────

enum class CardDisplayMode {
    ICON,   // square icon card (default)
    BANNER  // wide 16:9 banner card
}

// ── Row classification ────────────────────────────────────────────────────────

/**
 * CATEGORY rows = collections of apps (Favourites, All Apps, Custom, Recently Opened)
 * CHANNEL rows  = content listings sourced from inside apps via TvContract
 *                 (Continue Watching, Watch Next, etc.)
 */
enum class RowKind { CATEGORY, CHANNEL }

enum class CategoryType {
    ALL_APPS,
    FAVORITES,
    CUSTOM,
    RECENTLY_OPENED,
    SUGGESTIONS
}

enum class ChannelType {
    CONTINUE_WATCHING,  // TvContract WatchNextPrograms
    WATCH_NEXT,         // alias — same source, different label
    NEW_FOR_YOU,        // PreviewPrograms from installed apps
    CUSTOM_CHANNEL      // user-defined channel backed by a specific app
}

// ── Unified row descriptor ────────────────────────────────────────────────────

data class LauncherRow(
    val id: String,
    val title: String,
    val kind: RowKind,
    val categoryType: CategoryType? = null,
    val channelType: ChannelType?   = null,
    val apps: MutableList<String>   = mutableListOf(),
    val isVisible: Boolean          = true,
    val cardDisplayMode: CardDisplayMode = CardDisplayMode.ICON,
    // For CHANNEL rows: which app packages to include (empty = all)
    val allowedPackages: List<String> = emptyList(),
    val isPinnedToTop: Boolean      = false,
    // Per-row icon size override; null = use global setting
    val iconSizeLabelOverride: String? = null,
    // Auto-refresh interval for channel rows in minutes; 0 = manual only
    val autoRefreshMinutes: Int     = 0
)

// ── TV Content (Channel listings) ─────────────────────────────────────────────

data class TvContent(
    val id: String,
    val title: String,
    val subtitle: String,           // e.g. "S2 E4 • Netflix"
    val packageName: String,
    val deepLinkUri: String?,
    val artworkUri: String?,
    val progressMs: Long = 0,
    val durationMs: Long = 0,
    val channelType: ChannelType = ChannelType.CONTINUE_WATCHING
) {
    val progressPercent: Int
        get() = if (durationMs > 0 && progressMs > 0)
            ((progressMs * 100) / durationMs).toInt().coerceIn(0, 100)
        else 0
}

// ── Continue Watching (kept for repo compat) ──────────────────────────────────
typealias ContinueWatchingEntry = TvContent

// ── Widget ────────────────────────────────────────────────────────────────────

enum class WidgetType { CONTINUE_WATCHING, CLOCK, WEATHER, QUICK_SETTINGS }

data class PinnedWidget(
    val id: String,
    val type: WidgetType,
    val title: String,
    val position: Int = 0
)

// ── Theme ─────────────────────────────────────────────────────────────────────

data class LauncherTheme(
    val id: String                  = "default",
    val name: String                = "Dark",
    val backgroundColorHex: String  = "#0D0D0D",
    val accentColorHex: String      = "#E53935",
    val cardColorHex: String        = "#1E1E1E",
    val textPrimaryHex: String      = "#FFFFFF",
    val textSecondaryHex: String    = "#B0B0B0",
    val useDynamicColor: Boolean    = false,
    val backgroundImageUri: String? = null,
    val backgroundBlur: Int         = 0,
    val backgroundDim: Int          = 60
)

// ── Preferences ───────────────────────────────────────────────────────────────

data class LauncherPrefs(
    val rows: List<LauncherRow>             = defaultRows(),
    val pinnedWidgets: List<PinnedWidget>   = emptyList(),
    val theme: LauncherTheme                = LauncherTheme(),
    val clockEnabled: Boolean               = true,
    val dateEnabled: Boolean                = true,
    val clockFormat24h: Boolean             = false,
    val focusScaleEnabled: Boolean          = true,
    val focusScaleFactor: Float             = 1.12f,
    val rowLabelStyle: String               = "ABOVE",
    val iconSizeLabel: String               = "M",      // S=80 M=108 L=160 XL=200
    val cardCornerRadiusPercent: Int        = 50,
    val wallpaperUri: String?               = null,
    val wallpaperUris: List<String>         = emptyList(), // slideshow collection
    val wallpaperIntervalSeconds: Int       = 300,         // 5 min default
    val rowsBottomMarginPercent: Int        = 15,
    val rowStartPaddingDp: Int              = 24,
    val rowStartPaddingPercent: Int         = 20,
    val rowSpacingPercent: Int              = 20,  // vertical space between rows 0-100%
    val itemSpacingPercent: Int             = 10,  // horizontal space between items 0-100%
    val animationsEnabled: Boolean          = true,
    val globalCardDisplayMode: CardDisplayMode = CardDisplayMode.ICON
)

fun iconSizeDp(label: String): Int = when (label) {
    "S"  -> 80
    "M"  -> 108
    "L"  -> 160
    "XL" -> 200
    else -> 108
}

private fun defaultRows(): List<LauncherRow> = listOf(
    LauncherRow(
        id = "row_continue_watching",
        title = "Continue Watching",
        kind = RowKind.CHANNEL,
        channelType = ChannelType.CONTINUE_WATCHING
    ),
    LauncherRow(
        id = "row_watch_next",
        title = "Watch Next",
        kind = RowKind.CHANNEL,
        channelType = ChannelType.WATCH_NEXT
    ),
    LauncherRow(
        id = "row_all_apps",
        title = "All Apps",
        kind = RowKind.CATEGORY,
        categoryType = CategoryType.ALL_APPS
    )
)
