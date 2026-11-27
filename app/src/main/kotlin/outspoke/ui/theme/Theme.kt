package dev.brgr.outspoke.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Outspoke's one visual system.
 *
 * Both the companion app and the keyboard use the same "Graphite" tokens; the keyboard
 * gets its own slightly stronger neutral ramp because it floats above arbitrary host
 * apps and must separate its keys without shadows. Material You (dynamic colour) is not
 * used: a dictation keyboard has one signal colour, teal for "the mic is live", and
 * wallpaper-derived palettes would change it.
 */

// Companion app

private val AppLightColorScheme = lightColorScheme(
    primary = AppPrimaryLight, onPrimary = AppOnPrimaryLight,
    primaryContainer = AppPrimaryContainerLight, onPrimaryContainer = AppOnPrimaryContainerLight,
    secondary = AppSecondaryLight, onSecondary = AppOnSecondaryLight,
    secondaryContainer = AppSecondaryContainerLight, onSecondaryContainer = AppOnSecondaryContainerLight,
    // Tertiary is a second neutral-green, never a third hue: nothing in this app should
    // compete with the accent.
    tertiary = AppSecondaryLight, onTertiary = AppOnSecondaryLight,
    tertiaryContainer = AppSecondaryContainerLight, onTertiaryContainer = AppOnSecondaryContainerLight,
    error = AppErrorLight, onError = AppOnErrorLight,
    errorContainer = AppErrorContainerLight, onErrorContainer = AppOnErrorContainerLight,
    background = AppBackgroundLight, onBackground = AppOnBackgroundLight,
    surface = AppSurfaceLight, onSurface = AppOnSurfaceLight,
    surfaceVariant = AppSurfaceVariantLight, onSurfaceVariant = AppOnSurfaceVariantLight,
    surfaceContainerLowest = AppSurfaceLowestLight, surfaceContainerLow = AppSurfaceLowLight,
    surfaceContainer = AppSurfaceContainerLight, surfaceContainerHigh = AppSurfaceHighLight,
    surfaceContainerHighest = AppSurfaceHighestLight,
    surfaceDim = AppBackgroundLight, surfaceBright = AppSurfaceLowestLight,
    outline = AppOutlineLight, outlineVariant = AppOutlineVariantLight,
    inverseSurface = AppInverseSurfaceLight, inverseOnSurface = AppInverseOnSurfaceLight,
    inversePrimary = AppInversePrimaryLight,
    scrim = Color(0xFF000000),
)

private val AppDarkColorScheme = darkColorScheme(
    primary = AppPrimaryDark, onPrimary = AppOnPrimaryDark,
    primaryContainer = AppPrimaryContainerDark, onPrimaryContainer = AppOnPrimaryContainerDark,
    secondary = AppSecondaryDark, onSecondary = AppOnSecondaryDark,
    secondaryContainer = AppSecondaryContainerDark, onSecondaryContainer = AppOnSecondaryContainerDark,
    tertiary = AppSecondaryDark, onTertiary = AppOnSecondaryDark,
    tertiaryContainer = AppSecondaryContainerDark, onTertiaryContainer = AppOnSecondaryContainerDark,
    error = AppErrorDark, onError = AppOnErrorDark,
    errorContainer = AppErrorContainerDark, onErrorContainer = AppOnErrorContainerDark,
    background = AppBackgroundDark, onBackground = AppOnBackgroundDark,
    surface = AppSurfaceDark, onSurface = AppOnSurfaceDark,
    surfaceVariant = AppSurfaceVariantDark, onSurfaceVariant = AppOnSurfaceVariantDark,
    surfaceContainerLowest = AppSurfaceLowestDark, surfaceContainerLow = AppSurfaceLowDark,
    surfaceContainer = AppSurfaceContainerDark, surfaceContainerHigh = AppSurfaceHighDark,
    surfaceContainerHighest = AppSurfaceHighestDark,
    surfaceDim = AppBackgroundDark, surfaceBright = AppSurfaceHighestDark,
    outline = AppOutlineDark, outlineVariant = AppOutlineVariantDark,
    inverseSurface = AppInverseSurfaceDark, inverseOnSurface = AppInverseOnSurfaceDark,
    inversePrimary = AppInversePrimaryDark,
    scrim = Color(0xFF000000),
)

// Keyboard

private val KeyboardLightColorScheme = lightColorScheme(
    primary = KeyboardPrimaryLight, onPrimary = KeyboardOnPrimaryLight,
    primaryContainer = KeyboardPrimaryContainerLight, onPrimaryContainer = KeyboardOnPrimaryContainerLight,
    secondary = KeyboardSecondaryLight, onSecondary = KeyboardOnPrimaryLight,
    secondaryContainer = KeyboardSecondaryContainerLight, onSecondaryContainer = KeyboardOnSecondaryContainerLight,
    tertiary = KeyboardSecondaryLight, onTertiary = KeyboardOnPrimaryLight,
    tertiaryContainer = KeyboardSecondaryContainerLight, onTertiaryContainer = KeyboardOnSecondaryContainerLight,
    error = KeyboardErrorLight, onError = KeyboardOnErrorLight,
    errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
    background = KeyboardBackgroundLight, onBackground = KeyboardOnBackgroundLight,
    surface = KeyboardSurfaceLight, onSurface = KeyboardOnSurfaceLight,
    surfaceVariant = KeyboardSurfaceVariantLight, onSurfaceVariant = KeyboardOnSurfaceVariantLight,
    surfaceContainerLowest = KeyboardSurfaceLowestLight, surfaceContainerLow = KeyboardSurfaceLowLight,
    surfaceContainer = KeyboardSurfaceContainerLight, surfaceContainerHigh = KeyboardSurfaceHighLight,
    surfaceContainerHighest = KeyboardSurfaceHighestLight,
    outline = KeyboardOutlineLight, outlineVariant = KeyboardOutlineVariantLight,
    scrim = Color(0xFF000000),
)

private val KeyboardDarkColorScheme = darkColorScheme(
    primary = KeyboardPrimaryDark, onPrimary = KeyboardOnPrimaryDark,
    primaryContainer = KeyboardPrimaryContainerDark, onPrimaryContainer = KeyboardOnPrimaryContainerDark,
    secondary = KeyboardSecondaryDark, onSecondary = KeyboardOnPrimaryDark,
    secondaryContainer = KeyboardSecondaryContainerDark, onSecondaryContainer = KeyboardOnSecondaryContainerDark,
    tertiary = KeyboardSecondaryDark, onTertiary = KeyboardOnPrimaryDark,
    tertiaryContainer = KeyboardSecondaryContainerDark, onTertiaryContainer = KeyboardOnSecondaryContainerDark,
    error = KeyboardErrorDark, onError = KeyboardOnErrorDark,
    errorContainer = Color(0xFF6C2A24), onErrorContainer = Color(0xFFFFDAD5),
    background = KeyboardBackgroundDark, onBackground = KeyboardOnBackgroundDark,
    surface = KeyboardSurfaceDark, onSurface = KeyboardOnSurfaceDark,
    surfaceVariant = KeyboardSurfaceVariantDark, onSurfaceVariant = KeyboardOnSurfaceVariantDark,
    surfaceContainerLowest = KeyboardSurfaceLowestDark, surfaceContainerLow = KeyboardSurfaceLowDark,
    surfaceContainer = KeyboardSurfaceContainerDark, surfaceContainerHigh = KeyboardSurfaceHighDark,
    surfaceContainerHighest = KeyboardSurfaceHighestDark,
    outline = KeyboardOutlineDark, outlineVariant = KeyboardOutlineVariantDark,
    scrim = Color(0xFF000000),
)

// Status colours beyond the Material role set

/**
 * "This is done / healthy" colours, separate from [androidx.compose.material3.ColorScheme.primary]:
 * the success colour is distinct from the accent, which the keyboard reserves for the
 * live recording state.
 */
@Immutable
data class OutspokeStatusColors(
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    /** Fill/tint of the live "tap to stop" talk button; see [StopDark]. */
    val stop: Color,
    val onStop: Color,
)

private val StatusLight = OutspokeStatusColors(
    success = SuccessLight, successContainer = SuccessContainerLight, onSuccessContainer = OnSuccessContainerLight,
    warning = WarningLight, warningContainer = WarningContainerLight, onWarningContainer = OnWarningContainerLight,
    stop = StopLight, onStop = OnStopLight,
)
private val StatusDark = OutspokeStatusColors(
    success = SuccessDark, successContainer = SuccessContainerDark, onSuccessContainer = OnSuccessContainerDark,
    warning = WarningDark, warningContainer = WarningContainerDark, onWarningContainer = OnWarningContainerDark,
    stop = StopDark, onStop = OnStopDark,
)

/**
 * Success/"done" colours, provided by both themes. Read it with
 * `LocalOutspokeStatusColors.current.success`.
 */
val LocalOutspokeStatusColors = staticCompositionLocalOf { StatusLight }

// Surface roles

/**
 * Card and page fills for each scheme. These have no single Material role: on the light
 * background (#F3F6F5) a card is lighter than the page, while on the dark background the
 * page surface equals the background.
 */
@Immutable
data class OutspokeSurfaceRoles(
    /** Card / list-row background sitting on [androidx.compose.material3.ColorScheme.background]. */
    val card: Color,
)

private val SurfacesLight = OutspokeSurfaceRoles(card = AppSurfaceLight)
private val SurfacesDark = OutspokeSurfaceRoles(card = AppSurfaceContainerDark)
private val KeyboardSurfacesLight = OutspokeSurfaceRoles(card = KeyboardSurfaceLight)
private val KeyboardSurfacesDark = OutspokeSurfaceRoles(card = KeyboardSurfaceDark)

/** Read it with `LocalOutspokeSurfaceRoles.current.card`. */
val LocalOutspokeSurfaceRoles = staticCompositionLocalOf { SurfacesLight }

// Shapes

/**
 * One radius scale for the whole product: [OutspokeShapes.key] keyboard keys,
 * [OutspokeShapes.card] list rows and cards, [OutspokeShapes.dialog] dialogs.
 */
object OutspokeShapes {
    val key: RoundedCornerShape = RoundedCornerShape(12.dp)
    val card: RoundedCornerShape = RoundedCornerShape(16.dp)
    val dialog: RoundedCornerShape = RoundedCornerShape(20.dp)
    val chip: RoundedCornerShape = RoundedCornerShape(percent = 50)
}

private val OutspokeShapeScheme = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = OutspokeShapes.key,
    medium = OutspokeShapes.card,
    large = OutspokeShapes.dialog,
    extraLarge = RoundedCornerShape(28.dp),
)

// Themes

/** Theme for the companion (settings) app. Follows the system light/dark setting. */
@Composable
fun OutspokeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    OutspokeLocals(darkTheme = darkTheme, surfaces = if (darkTheme) SurfacesDark else SurfacesLight) {
        MaterialTheme(
            colorScheme = if (darkTheme) AppDarkColorScheme else AppLightColorScheme,
            typography = OutspokeTypography,
            shapes = OutspokeShapeScheme,
            content = content,
        )
    }
}

/**
 * Theme for the keyboard input view. Follows the system light/dark setting so the strip
 * reads correctly on both dark and light hosts, while keeping the brand accent identical
 * in both.
 */
@Composable
fun OutspokeKeyboardTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    OutspokeLocals(
        darkTheme = dark,
        surfaces = if (dark) KeyboardSurfacesDark else KeyboardSurfacesLight,
    ) {
        MaterialTheme(
            colorScheme = if (dark) KeyboardDarkColorScheme else KeyboardLightColorScheme,
            typography = OutspokeTypography,
            shapes = OutspokeShapeScheme,
            content = content,
        )
    }
}

@Composable
private fun OutspokeLocals(
    darkTheme: Boolean,
    surfaces: OutspokeSurfaceRoles,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalOutspokeStatusColors provides if (darkTheme) StatusDark else StatusLight,
        LocalOutspokeSurfaceRoles provides surfaces,
        content = content,
    )
}
