package dev.brgr.outspoke.settings.screens

import android.os.SystemClock
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.brgr.outspoke.R
import dev.brgr.outspoke.audio.MicInfo
import dev.brgr.outspoke.audio.MicScore
import dev.brgr.outspoke.settings.preferences.CalibrationState
import dev.brgr.outspoke.settings.preferences.MicCalibrationViewModel
import dev.brgr.outspoke.settings.preferences.MicResult
import dev.brgr.outspoke.ui.theme.LocalOutspokeStatusColors
import dev.brgr.outspoke.ui.theme.LocalOutspokeSurfaceRoles
import dev.brgr.outspoke.ui.theme.MyIcons
import dev.brgr.outspoke.ui.theme.OutspokeShapes
import dev.brgr.outspoke.ui.theme.tabularFigures
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Microphone calibration screen. Records a short "thanks" reference clip on every input
 * microphone, scores them by high-frequency energy + SNR, and persists the winner as the
 * preferred mic. Back navigation is handled by the settings nav host's top bar.
 */
@Composable
fun MicCalibrationScreen(
    viewModel: MicCalibrationViewModel = viewModel(),
) {
    val mics by viewModel.mics.collectAsState()
    val state by viewModel.state.collectAsState()
    val preferredMicId by viewModel.preferredMicId.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.calib_subtitle),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.calib_instructions),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))

        when (val s = state) {
            CalibrationState.Idle -> {
                if (mics.isEmpty()) {
                    Text(stringResource(R.string.calib_no_mics))
                } else {
                    mics.forEach { mic ->
                        MicRow(
                            mic = mic,
                            score = null,
                            isBest = false,
                            isCurrent = mic.id == preferredMicId,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    RecordButton(
                        onClick = viewModel::startCalibration,
                        label = stringResource(R.string.calib_start),
                        enabled = mics.isNotEmpty(),
                    )
                }
            }

            is CalibrationState.GetReady -> {
                val mic = mics.getOrNull(s.index)
                if (mic != null) {
                    CaptureStage(mic = mic, index = s.index, total = s.total, isLive = false)
                }
            }

            is CalibrationState.Recording -> {
                val mic = mics.getOrNull(s.index)
                if (mic != null) {
                    CaptureStage(mic = mic, index = s.index, total = s.total, isLive = true)
                }
            }

            is CalibrationState.Done -> {
                Text(
                    text = stringResource(R.string.calib_done),
                    style = MaterialTheme.typography.titleMedium,
                )
                s.results
                    .sortedByDescending { it.score.score }
                    .forEach { result ->
                        MicRow(
                            mic = result.mic,
                            score = result.score,
                            isBest = result.mic.id == s.bestMicId,
                            isCurrent = false,
                        )
                    }
                if (s.bestMicId != 0) {
                    // Saved confirmation pairs the success tint with a check glyph so the
                    // "done" reading survives without colour.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = MyIcons.CheckCircle,
                            contentDescription = null,
                            tint = LocalOutspokeStatusColors.current.success,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.calib_saved),
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalOutspokeStatusColors.current.success,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Button(
                        onClick = viewModel::reset,
                        shape = OutspokeShapes.chip,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        Text(
                            text = stringResource(R.string.calib_run_again),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }

            is CalibrationState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = s.message,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            // startCalibration() no-ops unless the state is Idle, so the
                            // error state must be cleared first; together these two calls
                            // restart the measurement pass through the normal start path.
                            onClick = {
                                viewModel.reset()
                                viewModel.startCalibration()
                            },
                            shape = OutspokeShapes.chip,
                        ) {
                            Text(
                                text = stringResource(R.string.action_retry),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A single microphone row. When [score] is non-null (calibration complete) the high-frequency
 * percentage and SNR are shown; [isBest] / [isCurrent] add a badge on the trailing edge.
 * The "Best" badge is a success-tonal chip carrying a check glyph, so the winner reads
 * without relying on its colour.
 */
@Composable
private fun MicRow(
    mic: MicInfo,
    score: MicScore?,
    isBest: Boolean,
    isCurrent: Boolean,
) {
    val statusColors = LocalOutspokeStatusColors.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = OutspokeShapes.card,
        colors = CardDefaults.cardColors(containerColor = LocalOutspokeSurfaceRoles.current.card),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = mic.label, style = MaterialTheme.typography.titleMedium)
                if (score != null) {
                    Text(
                        text = stringResource(
                            R.string.calib_score_format,
                            (score.hfFraction * 100f).roundToInt(),
                            score.snrDb.roundToInt(),
                        ),
                        // Scores differ between rows only by digits - tabular figures keep
                        // the columns aligned.
                        style = MaterialTheme.typography.bodySmall.tabularFigures,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when {
                isBest -> Surface(
                    shape = OutspokeShapes.chip,
                    color = statusColors.successContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = MyIcons.CheckCircle,
                            contentDescription = null,
                            tint = statusColors.onSuccessContainer,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.calib_best),
                            style = MaterialTheme.typography.labelMedium,
                            color = statusColors.onSuccessContainer,
                        )
                    }
                }
                isCurrent -> Text(
                    text = stringResource(R.string.calib_current),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A circular record-style button with a mic glyph and a caption beneath it. The accent
 * (`primary`) marks it as "the action that starts capture"; disabled, it drops to the
 * neutral variant fill.
 */
@Composable
private fun RecordButton(
    onClick: () -> Unit,
    label: String,
    enabled: Boolean,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .background(
                    color = if (enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                    shape = CircleShape,
                )
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MyIcons.Mic,
                contentDescription = label,
                tint = if (enabled) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The full-screen capture stage shown while a microphone is being calibrated. It tells the user
 * which mic is next / live, and — via [isLive] — whether to speak now ([isLive] = true) or to
 * prepare ([isLive] = false, the "get ready" pause between mics).
 */
@Composable
private fun CaptureStage(
    mic: MicInfo,
    index: Int,
    total: Int,
    isLive: Boolean,
) {
    val fraction = rememberCaptureProgress(index, isLive)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.calib_mic_of, index + 1, total),
            // The counter changes while this screen is on view.
            style = MaterialTheme.typography.labelLarge.tabularFigures,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = mic.label,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(28.dp))
        CaptureIndicator(isLive = isLive)
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(if (isLive) R.string.calib_speak_now else R.string.calib_get_ready),
            style = MaterialTheme.typography.headlineSmall,
            color = if (isLive) MaterialTheme.colorScheme.error
            else LocalOutspokeStatusColors.current.warning,
        )
        if (isLive) {
            Spacer(modifier = Modifier.height(20.dp))
            CaptureProgress(fraction = fraction)
        }
        Spacer(modifier = Modifier.height(28.dp))
        MicProgressDots(current = index, total = total, isLive = isLive)
    }
}

/**
 * Drives the capture progress [fraction] (0..1) over one [MicCalibrationViewModel.RECORD_MS]
 * window. Re-arms whenever [index] or [isLive] changes so each mic's bar starts empty.
 */
@Composable
private fun rememberCaptureProgress(index: Int, isLive: Boolean): Float {
    var fraction by remember { mutableStateOf(0f) }
    LaunchedEffect(index, isLive) {
        if (!isLive) {
            fraction = 0f
            return@LaunchedEffect
        }
        val duration = MicCalibrationViewModel.RECORD_MS.toLong()
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < duration) {
            delay(50L)
            fraction = ((SystemClock.elapsedRealtime() - start).toFloat() / duration).coerceIn(0f, 1f)
        }
        fraction = 1f
    }
    return fraction
}

/**
 * The large circular capture indicator. In the live state it's a solid `error` disc with an
 * expanding "ping" ring to signal "recording now"; otherwise it's a calm tonal `warningContainer`
 * standby. Both fills pair with their on-container role so the mic glyph keeps contrast in
 * light and dark.
 */
@Composable
private fun CaptureIndicator(isLive: Boolean) {
    val colorScheme = MaterialTheme.colorScheme
    val statusColors = LocalOutspokeStatusColors.current
    val color = if (isLive) colorScheme.error else statusColors.warningContainer
    val glyphTint = if (isLive) colorScheme.onError else statusColors.onWarningContainer
    val transition = rememberInfiniteTransition(label = "capture")
    val ping by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = EaseOutCubic),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ping",
    )
    val pingAlpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = EaseOutCubic),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pingAlpha",
    )
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (isLive) 1.07f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = EaseOutCubic),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scale",
    )

    Box(contentAlignment = Alignment.Center) {
        if (isLive) {
            Box(
                modifier = Modifier
                    .size(88.dp * ping)
                    .graphicsLayer {
                        alpha = pingAlpha
                    }
                    .background(color = color, shape = CircleShape),
            )
        }
        Box(
            modifier = Modifier
                .size(88.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .background(color = color, shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MyIcons.Mic,
                contentDescription = null,
                tint = glyphTint,
                modifier = Modifier.size(40.dp),
            )
        }
    }
}

/**
 * A determinate progress bar filling over the capture window. The fill is the accent -
 * same "capture is live" signal as the waveform and the keyboard talk button.
 */
@Composable
private fun CaptureProgress(fraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)),
        )
    }
}

/**
 * One dot per microphone: filled for captured, a ring for the current mic, outlined for the
 * upcoming ones.
 */
@Composable
private fun MicProgressDots(current: Int, total: Int, isLive: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for (i in 0 until total) {
            val state = when {
                i < current -> DotState.Done
                i == current -> DotState.Current
                else -> DotState.Upcoming
            }
            Box(
                modifier = Modifier
                    .size(if (state == DotState.Current) 14.dp else 10.dp)
                    .background(
                        color = when (state) {
                            DotState.Done -> LocalOutspokeStatusColors.current.success
                            DotState.Current -> if (isLive) MaterialTheme.colorScheme.error
                            else LocalOutspokeStatusColors.current.warning
                            DotState.Upcoming -> MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = CircleShape,
                    ),
            )
        }
    }
}

private enum class DotState { Done, Current, Upcoming }
