package dev.brgr.outspoke.ui.keyboard.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.brgr.outspoke.ui.theme.MyIcons
import dev.brgr.outspoke.ui.theme.OutspokeKeyboardTheme

/**
 * Total height of the suggestion bar chips row when visible (excludes the 1dp divider).
 * Used by [OutspokeInputMethodService] for the initial window-height sync on [onWindowShown].
 */
const val SUGGESTION_BAR_HEIGHT_DP = 36

// Chips row (24dp) + hairline divider (1dp)
private val SUGGESTION_BAR_TOTAL_HEIGHT_DP = SUGGESTION_BAR_HEIGHT_DP + 1

// ── Animation tuning ─────────────────────────────────────────────────────────

/** Duration of the appear / disappear animation in milliseconds. Increase to slow down. */
private const val SUGGESTION_BAR_ANIM_DURATION_MS = 300

/**
 * Easing for the reveal animation (hidden → visible): decelerating so the bar
 * glides in smoothly and settles at the top. CSS equivalent: ease-out.
 */
private val SuggestionBarShowEasing = CubicBezierEasing(0f, 0f, 0.2f, 1f)

/**
 * Easing for the dismiss animation (visible → hidden): accelerating so the bar
 * snaps away quickly. CSS equivalent: ease-in.
 */
private val SuggestionBarHideEasing = CubicBezierEasing(0.4f, 0f, 1f, 1f)

/**
 * Horizontal strip of word-suggestion chips with a visual divider and a dismiss button.
 *
 * ## Animation strategy — no window resize during animation
 *
 * The IME window is pre-sized to include the full bar slot before the animation starts
 * (on show) or keeps its size until after the animation completes (on hide). This means
 * the underlying app (e.g. WhatsApp) receives exactly **one** layout event per show/hide
 * cycle — not one per animation frame — eliminating the per-frame jank.
 *
 * The bar itself animates purely with a `graphicsLayer` Y-translation: when hidden it is
 * translated downward by its full height (tucked below the slot into the keyboard body),
 * and when visible it rests at translationY = 0. The effect is a rising-sun from the
 * keyboard horizon — the bar rises upward into view and slides back down to hide.
 *
 * [onWindowSizeTarget] is called with the **desired window add-on height** (0 or full bar
 * height in px) the moment the visibility target changes — before the visual animation
 * begins. The IME service uses this to resize the window once, immediately, so the app
 * below gets a single layout event up front and then sees smooth visual animation.
 *
 * @param suggestions        Alternative word candidates returned by the system spell-checker.
 * @param dismissed          `true` after the user tapped the dismiss button.
 * @param onWindowSizeTarget Called immediately when the bar's visibility target changes.
 *                           `true` = bar should be shown (service grows window now).
 *                           `false` = bar is hiding (service shrinks window after animation).
 * @param onSuggestionTapped Called with the tapped suggestion; replaces the cursor word.
 * @param onDismiss          Called when the user taps the × button.
 * @param modifier           Optional [Modifier] applied to the outer wrapper.
 * @param dismissContentDescription Accessibility label for the × button.
 */
@Composable
fun SuggestionBar(
    suggestions: List<String>,
    dismissed: Boolean,
    onSuggestionTapped: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissContentDescription: String? = null,
    onWindowSizeTarget: (Int) -> Unit = {},
) {
    val visible = suggestions.isNotEmpty() && !dismissed

    // Notify the service when the visibility target changes so it can resize the window
    // once (grow immediately on show; shrink after animation delay on hide).
    // -1 is the sentinel for "show" — the service resolves it to barSlotHeightPx.
    LaunchedEffect(visible) {
        onWindowSizeTarget(if (visible) -1 else 0)
    }

    // Animate the Y-translation: 0 = fully visible, +(full height) = hidden below the slot
    // (tucked behind the keyboard body). The bar rises upward into view on show, and slides
    // back down and away on hide — like a rising sun from the keyboard horizon.
    // Layout size never changes — only graphicsLayer translationY moves.
    val animatedTranslationDp by animateDpAsState(
        targetValue = if (visible) 0.dp else SUGGESTION_BAR_TOTAL_HEIGHT_DP.dp,
        animationSpec = tween(
            durationMillis = SUGGESTION_BAR_ANIM_DURATION_MS,
            easing = if (visible) SuggestionBarShowEasing else SuggestionBarHideEasing,
        ),
        label = "suggestionBarTranslation",
    )

    // The slot always occupies its full height in the layout — the keyboard content below
    // is never displaced during animation. clipToBounds hides the bar when translated away.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(SUGGESTION_BAR_TOTAL_HEIGHT_DP.dp)
            .clipToBounds(),
    ) {
        Column(
            modifier = Modifier.graphicsLayer {
                translationY = animatedTranslationDp.toPx()
            },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SUGGESTION_BAR_HEIGHT_DP.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Scrollable chip list — grows to fill available width left of dismiss icon.
                LazyRow(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(suggestions) { suggestion ->
                        SuggestionChip(
                            onClick = { onSuggestionTapped(suggestion) },
                            label = {
                                Text(
                                    text = suggestion,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                            ),
                            elevation = SuggestionChipDefaults.suggestionChipElevation(elevation = 1.dp),
                        )
                    }
                }

                // Dismiss button — compact icon button on the trailing edge.
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = MyIcons.Close,
                        contentDescription = dismissContentDescription,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            // Hairline divider — visually separates suggestion area from keyboard buttons.
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

// ── Previews ─────────────────────────────────────────────────────────────────

/**
 * Renders the suggestion bar content unconditionally (no [AnimatedVisibility] wrapper),
 * so the IntelliJ preview tool always has something to show regardless of the [visible]
 * state.  Only used in preview functions — never ship this in production composables.
 */
@Composable
private fun SuggestionBarContent(suggestions: List<String>) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(SUGGESTION_BAR_HEIGHT_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LazyRow(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(suggestions) { suggestion ->
                    SuggestionChip(
                        onClick = {},
                        label = {
                            Text(
                                text = suggestion,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                        elevation = SuggestionChipDefaults.suggestionChipElevation(elevation = 1.dp),
                    )
                }
            }
            IconButton(onClick = {}, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = MyIcons.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111111, name = "SuggestionBar – empty (no bar)")
@Composable
private fun SuggestionBarEmptyPreview() {
    OutspokeKeyboardTheme {
        // When suggestions is empty the bar renders nothing; show a placeholder box
        // so the preview pane is not blank.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(SUGGESTION_BAR_HEIGHT_DP.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "(no suggestions — bar is hidden)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111111, name = "SuggestionBar – with chips")
@Composable
private fun SuggestionBarWithChipsPreview() {
    OutspokeKeyboardTheme {
        // Bypass AnimatedVisibility so IntelliJ renders the content immediately.
        SuggestionBarContent(suggestions = listOf("hello", "hallo", "hollow"))
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111111, name = "SuggestionBar – dismissed (no bar)")
@Composable
private fun SuggestionBarDismissedPreview() {
    OutspokeKeyboardTheme {
        // Dismissed → bar hidden; show placeholder identical to the empty preview.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(SUGGESTION_BAR_HEIGHT_DP.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "(dismissed — bar is hidden)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
