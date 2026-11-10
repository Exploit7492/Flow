package io.github.aedev.flow.notification

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.toArgb
import io.github.aedev.flow.data.local.LocalDataManager
import io.github.aedev.flow.ui.theme.ThemeVariant
import io.github.aedev.flow.ui.theme.resolveFlowColorScheme
import io.github.aedev.flow.ui.theme.resolveFlowThemeSlot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * The app's active accent, for a notification's `setColor` and the progress bar's tint. Resolved
 * through the same [resolveFlowColorScheme] the in-app theme and the home-screen widgets use, so a
 * download's notification carries the palette the user picked rather than the system default.
 *
 * Resolved once per process and cached: the queue posts many notifications and the stored theme does
 * not change mid-download.
 */
object NotificationTheming {
    /** Flow Default's dark primary, for a notification posted before any theme has been stored. */
    private val FALLBACK_ACCENT = 0xFFFF0000.toInt()

    @Volatile
    private var cached: Int? = null

    fun accent(context: Context): Int {
        cached?.let { return it }
        val resolved = resolve(context.applicationContext)
        cached = resolved
        return resolved
    }

    private fun resolve(appContext: Context): Int =
        runCatching {
            val data = LocalDataManager(appContext)
            val systemDark = appContext.isSystemDark()
            runBlocking {
                val mode = data.themeMode.first()
                val variant = data.themeVariant.first()
                val custom = data.activeCustomTheme.first()
                val systemLight = data.systemLightThemeMode.first()
                val systemDarkMode = data.systemDarkThemeMode.first()
                val systemDarkVariant = data.systemDarkThemeVariant.first()
                val slot = resolveFlowThemeSlot(systemDark, mode, variant, systemLight, systemDarkMode, systemDarkVariant)
                resolveFlowColorScheme(
                    context = appContext,
                    isSystemDark = slot.variant != ThemeVariant.LIGHT,
                    themeMode = mode,
                    themeVariant = variant,
                    customTheme = custom,
                    systemLightThemeMode = systemLight,
                    systemDarkThemeMode = systemDarkMode,
                    systemDarkThemeVariant = systemDarkVariant,
                ).primary.toArgb()
            }
        }.getOrDefault(FALLBACK_ACCENT)

    /** The device's own light/dark setting, which a SYSTEM theme follows. */
    private fun Context.isSystemDark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
}
