package dev.brgr.outspoke.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Outspoke brand tokens - "Graphite" system.
 *
 * Cool graphite neutrals with a faint green cast, one teal accent used only for the
 * primary action and the live recording state. Every Material 3 role is defined
 * explicitly for both themes: an unset role falls back to the Material baseline
 * purple.
 *
 * Contrast (WCAG): normal text >= 7:1 on both themes, `primary` >= 4.5:1 against its
 * own surface in both directions, `error` >= 4.5:1 in both directions.
 */

// Companion app, light
internal val AppPrimaryLight = Color(0xFF00695C)
internal val AppOnPrimaryLight = Color(0xFFFFFFFF)
internal val AppPrimaryContainerLight = Color(0xFFB9EFE1)
internal val AppOnPrimaryContainerLight = Color(0xFF00201A)
internal val AppSecondaryLight = Color(0xFF4A635D)
internal val AppOnSecondaryLight = Color(0xFFFFFFFF)
internal val AppSecondaryContainerLight = Color(0xFFCCE8E1)
internal val AppOnSecondaryContainerLight = Color(0xFF06201B)
internal val AppErrorLight = Color(0xFFB3261E)
internal val AppOnErrorLight = Color(0xFFFFFFFF)
internal val AppErrorContainerLight = Color(0xFFF9DEDC)
internal val AppOnErrorContainerLight = Color(0xFF410E0B)
internal val AppBackgroundLight = Color(0xFFF3F6F5)
internal val AppOnBackgroundLight = Color(0xFF101A19)
internal val AppSurfaceLight = Color(0xFFFFFFFF)
internal val AppOnSurfaceLight = Color(0xFF101A19)
internal val AppSurfaceVariantLight = Color(0xFFE7EDEB)
internal val AppOnSurfaceVariantLight = Color(0xFF44514E)
internal val AppSurfaceLowestLight = Color(0xFFFFFFFF)
internal val AppSurfaceLowLight = Color(0xFFF2F5F4)
internal val AppSurfaceContainerLight = Color(0xFFECEFEE)
internal val AppSurfaceHighLight = Color(0xFFE6EAE9)
internal val AppSurfaceHighestLight = Color(0xFFE0E5E3)
internal val AppOutlineLight = Color(0xFF6F7B78)
internal val AppOutlineVariantLight = Color(0xFFDCE2E0)
internal val AppInverseSurfaceLight = Color(0xFF26312F)
internal val AppInverseOnSurfaceLight = Color(0xFFEDEFEC)
internal val AppInversePrimaryLight = Color(0xFF6FDBBE)

// Companion app, dark
internal val AppPrimaryDark = Color(0xFF6FDDC0)
internal val AppOnPrimaryDark = Color(0xFF00382F)
internal val AppPrimaryContainerDark = Color(0xFF174E43)
internal val AppOnPrimaryContainerDark = Color(0xFF8DF6D8)
internal val AppSecondaryDark = Color(0xFFB4CCC4)
internal val AppOnSecondaryDark = Color(0xFF1E352F)
internal val AppSecondaryContainerDark = Color(0xFF354C46)
internal val AppOnSecondaryContainerDark = Color(0xFFD0E8E1)
internal val AppErrorDark = Color(0xFFFFB4AB)
internal val AppOnErrorDark = Color(0xFF690005)
internal val AppErrorContainerDark = Color(0xFF93000A)
internal val AppOnErrorContainerDark = Color(0xFFFFDAD6)
internal val AppBackgroundDark = Color(0xFF0F1413)
internal val AppOnBackgroundDark = Color(0xFFE0E9E5)
internal val AppSurfaceDark = Color(0xFF0F1413)
internal val AppOnSurfaceDark = Color(0xFFE0E9E5)
internal val AppSurfaceVariantDark = Color(0xFF33403B)
internal val AppOnSurfaceVariantDark = Color(0xFFB0BDB8)
internal val AppSurfaceLowestDark = Color(0xFF0A0F0E)
internal val AppSurfaceLowDark = Color(0xFF171C1B)
internal val AppSurfaceContainerDark = Color(0xFF1B2120)
internal val AppSurfaceHighDark = Color(0xFF232928)
internal val AppSurfaceHighestDark = Color(0xFF2B3230)
internal val AppOutlineDark = Color(0xFF8A9692)
internal val AppOutlineVariantDark = Color(0xFF3A4542)
internal val AppInverseSurfaceDark = Color(0xFFE0E9E5)
internal val AppInverseOnSurfaceDark = Color(0xFF26312F)
internal val AppInversePrimaryDark = Color(0xFF00695C)

// Keyboard, light
// The keyboard floats above arbitrary host apps, so it keeps its own, slightly
// stronger neutral ramp: keys must separate from the strip without shadows.
internal val KeyboardPrimaryLight = Color(0xFF00695C)
internal val KeyboardOnPrimaryLight = Color(0xFFFFFFFF)
internal val KeyboardPrimaryContainerLight = Color(0xFFB9EFE1)
internal val KeyboardOnPrimaryContainerLight = Color(0xFF00201A)
internal val KeyboardSecondaryLight = Color(0xFF4A635D)
internal val KeyboardSecondaryContainerLight = Color(0xFFD7E5E1)
internal val KeyboardOnSecondaryContainerLight = Color(0xFF06201B)
internal val KeyboardErrorLight = Color(0xFFB3261E)
internal val KeyboardOnErrorLight = Color(0xFFFFFFFF)
internal val KeyboardBackgroundLight = Color(0xFFE8EDEC)
internal val KeyboardOnBackgroundLight = Color(0xFF101A19)
internal val KeyboardSurfaceLight = Color(0xFFFFFFFF)
internal val KeyboardOnSurfaceLight = Color(0xFF101A19)
internal val KeyboardSurfaceVariantLight = Color(0xFFE4EAE8)
internal val KeyboardOnSurfaceVariantLight = Color(0xFF4A5654)
internal val KeyboardOutlineLight = Color(0xFF6F7B78)
internal val KeyboardOutlineVariantLight = Color(0xFFCBD5D2)
// Keyboard tonal ladder: the strip sits on `background`, keys on `surface`, and the
// suggestion shelf / pressed keys need steps that stay inside the strip's own cool cast
// instead of Material's baseline containers (which are violet-tinted by default).
internal val KeyboardSurfaceLowestLight = Color(0xFFFFFFFF)
internal val KeyboardSurfaceLowLight = Color(0xFFF1F5F4)
internal val KeyboardSurfaceContainerLight = Color(0xFFECEFEE)
internal val KeyboardSurfaceHighLight = Color(0xFFE6EBEA)
internal val KeyboardSurfaceHighestLight = Color(0xFFE0E6E5)

// Keyboard, dark
internal val KeyboardPrimaryDark = Color(0xFF5FDBBE)
internal val KeyboardOnPrimaryDark = Color(0xFF00382F)
internal val KeyboardPrimaryContainerDark = Color(0xFF174E43)
internal val KeyboardOnPrimaryContainerDark = Color(0xFF8DF6D8)
internal val KeyboardSecondaryDark = Color(0xFFB4CCC4)
internal val KeyboardSecondaryContainerDark = Color(0xFF2C433D)
internal val KeyboardOnSecondaryContainerDark = Color(0xFFD0E8E1)
internal val KeyboardErrorDark = Color(0xFFFFB4AB)
internal val KeyboardOnErrorDark = Color(0xFF4A120D)
internal val KeyboardBackgroundDark = Color(0xFF0D1211)
internal val KeyboardOnBackgroundDark = Color(0xFFE0E9E5)
internal val KeyboardSurfaceDark = Color(0xFF161B1A)
internal val KeyboardOnSurfaceDark = Color(0xFFE0E9E5)
internal val KeyboardSurfaceVariantDark = Color(0xFF222B29)
internal val KeyboardOnSurfaceVariantDark = Color(0xFFAEB9B5)
internal val KeyboardOutlineDark = Color(0xFF8A9692)
internal val KeyboardOutlineVariantDark = Color(0xFF2C3634)
internal val KeyboardSurfaceLowestDark = Color(0xFF0A0F0E)
internal val KeyboardSurfaceLowDark = Color(0xFF121817)
internal val KeyboardSurfaceContainerDark = Color(0xFF161C1B)
internal val KeyboardSurfaceHighDark = Color(0xFF1C2322)
internal val KeyboardSurfaceHighestDark = Color(0xFF232A29)

/**
 * Semantic status colour for "this step is done". Success is a separate colour from the
 * accent, which on the keyboard marks the live recording state. Tinted neutrals plus a
 * check glyph carry it, so colour is never the only signal.
 */
internal val SuccessLight = Color(0xFF0F5C3F)
internal val SuccessContainerLight = Color(0xFFD6ECDF)
internal val OnSuccessContainerLight = Color(0xFF052A1C)
internal val SuccessDark = Color(0xFF86D9AC)
internal val SuccessContainerDark = Color(0xFF1D4231)
internal val OnSuccessContainerDark = Color(0xFFC8F2D9)
// Container-grade red for the live talk button. The Material `error` role is a foreground
// colour in dark mode (pastel #FFB4AB), which on a 72 dp filled control reads as disabled
// rather than recording.
internal val StopLight = Color(0xFFB3261E)
internal val OnStopLight = Color(0xFFFFFFFF)
internal val StopDark = Color(0xFFD93C30)
internal val OnStopDark = Color(0xFFFFFFFF)

internal val WarningLight = Color(0xFFA15C07)
internal val WarningContainerLight = Color(0xFFFDECC8)
internal val OnWarningContainerLight = Color(0xFF3A2604)
internal val WarningDark = Color(0xFFF0B45A)
internal val WarningContainerDark = Color(0xFF4A3512)
internal val OnWarningContainerDark = Color(0xFFFBE3B4)
