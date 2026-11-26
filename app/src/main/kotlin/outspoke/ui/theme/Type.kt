package dev.brgr.outspoke.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Outspoke type scale - system sans (Roboto) with Material 3 roles, tightened for a
 * utility app.
 *
 * Two decisions matter on a keyboard strip:
 *  - every role is defined, so nothing silently renders at an unspecified default size;
 *  - body/label sizes stay >= 12 sp, because the IME row is read at arm's length in a
 *    glance, not on a settings page.
 */
val OutspokeTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = (-0.4).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp, lineHeight = 34.sp, letterSpacing = (-0.3).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 15.5.sp, lineHeight = 21.sp, letterSpacing = 0.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 15.5.sp, lineHeight = 22.sp, letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp, lineHeight = 17.sp, letterSpacing = 0.2.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 12.5.sp, lineHeight = 18.sp, letterSpacing = 0.3.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 11.5.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp,
    ),
)

/**
 * Tabular figures for anything numeric that changes while the user watches it -
 * download percentages, diagnostics counters, calibration scores. Prevents the row from
 * jittering as digits change width.
 */
val TextStyle.tabularFigures: TextStyle
    get() = copy(fontFeatureSettings = if (fontFeatureSettings.isNullOrBlank()) "tnum" else "$fontFeatureSettings,tnum")
