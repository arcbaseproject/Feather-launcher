package dev.feather.launcher

import android.app.UiModeManager
import android.content.Context

object Prefs {
    const val THEME = "theme" // 0 system, 1 light, 2 dark
    const val ICON_DP = "icon_dp"
    const val LABELS = "labels"
    const val HOME_COLS = "home_cols"
    const val HOME_ROWS = "home_rows"
    const val DRAWER_COLS = "drawer_cols"
    const val ANIM = "animations"
    const val FOCUS = "autofocus"
    const val LOW_MEM = "low_mem"

    const val D_ICON_DP = 52
    const val D_COLS = 4
    const val D_ROWS = 5

    /** Settings only. Home layout lives in a separate file so editing it doesn't look like a settings change. */
    fun get(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun layout(c: Context) = c.getSharedPreferences("layout", Context.MODE_PRIVATE)

    /** The system persists this per app and recreates activities itself. */
    fun applyTheme(c: Context, v: Int) = c.getSystemService(UiModeManager::class.java).setApplicationNightMode(
        when (v) {
            1 -> UiModeManager.MODE_NIGHT_NO
            2 -> UiModeManager.MODE_NIGHT_YES
            else -> UiModeManager.MODE_NIGHT_AUTO
        }
    )
}
