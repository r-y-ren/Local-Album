package com.renyxin.localalbum.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * 主题模式：
 *
 * - [System] 跟随系统自动切换
 * - [Light] 始终浅色模式
 * - [Dark] 始终深色模式
 */
enum class ThemeMode { System, Light, Dark }

/** 持久化的主题模式取值：0=System，1=Light，2=Dark（SettingsStore 写入时 coerceIn(0, 2)）。 */
private const val THEME_MODE_SYSTEM = 0
private const val THEME_MODE_LIGHT = 1
private const val THEME_MODE_DARK = 2

/**
 * 将持久化 Int 还原为 [ThemeMode]（MainActivity 与 LocalAlbumApp 共享的唯一映射）。
 * 越界/未知值一律回退 [ThemeMode.System]。
 */
fun themeModeFromPersisted(mode: Int): ThemeMode = when (mode) {
    THEME_MODE_LIGHT -> ThemeMode.Light
    THEME_MODE_DARK -> ThemeMode.Dark
    else -> ThemeMode.System
}

/** [ThemeMode] 对应的持久化 Int（与 [themeModeFromPersisted] 互逆）。 */
fun themeModePersistedValue(mode: ThemeMode): Int = when (mode) {
    ThemeMode.System -> THEME_MODE_SYSTEM
    ThemeMode.Light -> THEME_MODE_LIGHT
    ThemeMode.Dark -> THEME_MODE_DARK
}

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1A73E8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF001D36),
    secondary = Color(0xFF5F6368),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8EAED),
    onSecondaryContainer = Color(0xFF1F1F1F),
    tertiary = Color(0xFF188038),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCEEAD6),
    onTertiaryContainer = Color(0xFF001F02),
    error = Color(0xFFD93025),
    onError = Color.White,
    errorContainer = Color(0xFFFCE8E6),
    onErrorContainer = Color(0xFF3B0400),
    background = Color(0xFFF8F9FA),
    onBackground = Color(0xFF202124),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF202124),
    surfaceVariant = Color(0xFFF1F3F4),
    onSurfaceVariant = Color(0xFF5F6368),
    surfaceContainerLow = Color(0xFFF8F9FA),
    surfaceContainer = Color(0xFFF1F3F4),
    surfaceContainerHigh = Color(0xFFE8EAED),
    outline = Color(0xFF80868B),
    outlineVariant = Color(0xFFDADCE0),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF0842A0),
    onPrimaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFFBDC1C6),
    onSecondary = Color(0xFF303134),
    secondaryContainer = Color(0xFF47484A),
    onSecondaryContainer = Color(0xFFE8EAED),
    tertiary = Color(0xFF81C995),
    onTertiary = Color(0xFF053A16),
    tertiaryContainer = Color(0xFF0D652D),
    onTertiaryContainer = Color(0xFFCEEAD6),
    error = Color(0xFFF28B82),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFFCE8E6),
    background = Color(0xFF202124),
    onBackground = Color(0xFFE8EAED),
    surface = Color(0xFF303134),
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF3C4043),
    onSurfaceVariant = Color(0xFFBDC1C6),
    surfaceContainerLow = Color(0xFF202124),
    surfaceContainer = Color(0xFF303134),
    surfaceContainerHigh = Color(0xFF3C4043),
    outline = Color(0xFF9AA0A6),
    outlineVariant = Color(0xFF44474B),
)

@Composable
fun LocalAlbumTheme(
    themeMode: ThemeMode = ThemeMode.System,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
    }

    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(LocalContext.current)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !darkTheme -> dynamicLightColorScheme(LocalContext.current)
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}