package dev.brgr.outspoke.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

@Composable
fun OutspokeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

// Fixed dark colour scheme for the keyboard - never uses dynamic colour or the
// system light/dark setting so the keyboard always looks consistent.
private val KeyboardColorScheme = darkColorScheme(
    primary = KeyboardAccent,
    onPrimary = KeyboardOnAccent,
    background = KeyboardBackground,
    onBackground = KeyboardOnSurface,
    surface = KeyboardSurface,
    onSurface = KeyboardOnSurface,
    surfaceVariant = KeyboardSurfaceVariant,
    onSurfaceVariant = KeyboardOnSurfaceVariant,
    error = KeyboardError,
)

@Composable
fun OutspokeKeyboardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = KeyboardColorScheme,
        typography = Typography,
        content = content,
    )
}


