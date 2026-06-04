package com.shrine.launcher.data.model

import android.graphics.drawable.Drawable

data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val isSystemApp: Boolean = false
)

enum class CardDisplayMode { ICON, BANNER }

enum class RowKind { CATEGORY, CHANNEL }

/** What happens when the idle timer fires (mirrors Projectivy's key_internal_idle_action). */
enum class IdleAction { BLANK_SCREEN, SCREENSAVER }

enum class CategoryType {
    ALL_APPS,
    FAVORITES,
    INSTALL,
    CUSTOM,
    RECENTLY_OPENED
}

enum class ChannelType {
    CONTINUE_WATCHING,
    WATCH_NEXT,
    NEW_FOR_YOU,
    TV_PROVIDER   // individual TvProvider channel row
}

data class LauncherRow(
    val id: String,
    val title: String,
    val kind: RowKind,
    val categoryType: CategoryType? = null,
    val channelType: ChannelType?   = null,
    val apps: MutableList<String>   = mutableListOf(),
    val isVisible: Boolean          = true,
    val cardDisplayMode: CardDisplayMode = CardDisplayMode.ICON,
    val allowedPackages: List<String> = emptyList(),
    val isPinnedToTop: Boolean      = false,
    val iconSizeLabelOverride: String? = null,
    val autoRefreshMinutes: Int     = 0,
    val tvProviderChannelId: Long?  = null   // only for ChannelType.TV_PROVIDER rows
)

data class TvContent(
    val id: String,
    val title: String,
    val subtitle: String,
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

typealias ContinueWatchingEntry = TvContent

enum class WidgetType { CONTINUE_WATCHING, CLOCK, WEATHER, QUICK_SETTINGS }

data class PinnedWidget(
    val id: String,
    val type: WidgetType,
    val title: String,
    val position: Int = 0
)

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

data class LauncherPrefs(
    val rows: List<LauncherRow>             = defaultRows(),
    val pinnedWidgets: List<PinnedWidget>   = emptyList(),
    val theme: LauncherTheme                = LauncherTheme(),
    val clockEnabled: Boolean               = true,
    val dateEnabled: Boolean                = true,
    val clockFormat24h: Boolean             = false,
    val rowLabelStyle: String               = "ABOVE",
    val iconSizeLabel: String               = "M",
    val cardCornerRadiusPercent: Int        = 50,
    val wallpaperUri: String?               = null,
    val wallpaperUris: List<String>         = emptyList(),
    val wallpaperIntervalSeconds: Int       = 300,
    val rowsBottomMarginPercent: Int        = 15,
    val rowStartPaddingDp: Int              = 24,
    val rowSpacingPercent: Int              = 20,
    val itemSpacingPercent: Int             = 10,
    val animationsEnabled: Boolean          = true,
    val globalCardDisplayMode: CardDisplayMode = CardDisplayMode.ICON,
    // New in v0.10.0
    val channelsEnabled: Boolean            = true,
    val showCategoryTitle: Boolean          = true,
    val showAppTitle: Boolean               = true,
    val statusBarIconSizePercent: Int       = 100,
    val idleModeEnabled: Boolean            = false,
    val idleTimeoutSeconds: Int             = 120,
    val idleAction: IdleAction              = IdleAction.BLANK_SCREEN,
    // Projectivy: double-tap BACK within 400 ms → launch screensaver
    val doubleBackToScreensaver: Boolean    = false,
    val progressBarEnabled: Boolean         = true,
    val wallpaperSlideshow: Boolean         = false,
    val showWifiButton: Boolean             = false,
    val defaultRowId: String?               = null
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
    ),
    LauncherRow(
        id = "row_favourites",
        title = "Favourites",
        kind = RowKind.CATEGORY,
        categoryType = CategoryType.FAVORITES
    ),
    LauncherRow(
        id = "row_install",
        title = "Install",
        kind = RowKind.CATEGORY,
        categoryType = CategoryType.INSTALL
    )
)
