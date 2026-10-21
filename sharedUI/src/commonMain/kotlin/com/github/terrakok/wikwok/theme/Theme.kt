package com.github.terrakok.wikwok.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Applies the Wikiscroll palette.
 *
 * The colours are two fixed schemes rather than one generated from a seed. The feed is dark on
 * purpose, the identity does not change between sessions, and a colour-generation library would add
 * a dependency and a runtime computation for a result that is constant by design.
 *
 * @param onThemeChanged reports the resolved brightness so the host can match its chrome.
 * @param content themed content.
 */
@Composable
internal fun AppTheme(
    onThemeChanged: @Composable (isDark: Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    onThemeChanged(isDark)
    MaterialTheme(
        colorScheme = if (isDark) DarkScheme else LightScheme,
        content = { Surface(content = content) },
    )
}

/** Dark scheme: the default, because the feed is meant to be read in the dark. */
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF9ED3BE),
    onPrimary = Color(0xFF10312A),
    secondary = Color(0xFFB9C7C1),
    onSecondary = Color(0xFF243430),
    background = Color(0xFF121212),
    onBackground = Color(0xFFECECEC),
    surface = Color(0xFF1B1B1B),
    onSurface = Color(0xFFECECEC),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFC4C9C6),
    error = Color(0xFFCF6679),
)

/** Light scheme, for readers whose system asks for it. */
private val LightScheme = lightColorScheme(
    primary = Color(0xFF2F6F5C),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF4A635B),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFEDEDED),
    onSurfaceVariant = Color(0xFF444746),
    error = Color(0xFFBA1A1A),
)
