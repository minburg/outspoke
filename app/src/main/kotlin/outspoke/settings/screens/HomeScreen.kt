package dev.brgr.outspoke.settings.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.brgr.outspoke.R
import dev.brgr.outspoke.audio.PermissionHelper
import dev.brgr.outspoke.inference.InferenceService
import dev.brgr.outspoke.settings.model.ModelRegistry
import dev.brgr.outspoke.settings.model.ModelStorageManager
import dev.brgr.outspoke.ui.theme.LocalOutspokeStatusColors
import dev.brgr.outspoke.ui.theme.LocalOutspokeSurfaceRoles
import dev.brgr.outspoke.ui.theme.MyIcons
import dev.brgr.outspoke.ui.theme.OutspokeShapes
import dev.brgr.outspoke.ui.theme.OutspokeTheme

/**
 * The home / dashboard screen of the Outspoke companion app.
 *
 * Shows the three setup requirements the user must fulfil before the keyboard works:
 *  1. IME enabled in system settings
 *  2. Microphone permission granted
 *  3. Model downloaded
 *
 * All three statuses are re-evaluated every time the screen comes into the foreground
 * so that changes made in system Settings or the file system are reflected immediately.
 */
@Composable
fun HomeScreen(
    onNavigateToModel: () -> Unit,
    onNavigateToInput: () -> Unit,
    onNavigateToSpeech: () -> Unit,
    onNavigateToTools: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Initialise from the live sources of truth (all three checks are
    // synchronous and cheap) rather than from defaults. After a process-death
    // relaunch the ON_RESUME event may already have fired before this observer
    // is registered, so relying on it alone leaves the screen stuck on the
    // unconfigured view.
    var isImeEnabled by remember { mutableStateOf(isOutspokeImeEnabled(context)) }
    var hasMicPermission by remember { mutableStateOf(PermissionHelper.hasRecordPermission(context)) }
    var isModelReady by remember { mutableStateOf(ModelRegistry.all.any { ModelStorageManager.isModelReady(context, it) }) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasMicPermission = granted
        if (granted && ModelStorageManager.isModelReady(context)) {
            context.startForegroundService(Intent(context, InferenceService::class.java))
        }
    }

    // Refresh all statuses on every ON_RESUME - catches changes made in other apps/settings.
    DisposableEffect(lifecycle) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                isImeEnabled = isOutspokeImeEnabled(context)
                hasMicPermission = PermissionHelper.hasRecordPermission(context)
                isModelReady = ModelRegistry.all.any { ModelStorageManager.isModelReady(context, it) }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    HomeScreenContent(
        isImeEnabled = isImeEnabled,
        hasMicPermission = hasMicPermission,
        isModelReady = isModelReady,
        onOpenImeSettings = { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
        onRequestMicPermission = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        onNavigateToModel = onNavigateToModel,
        onNavigateToInput = onNavigateToInput,
        onNavigateToSpeech = onNavigateToSpeech,
        onNavigateToTools = onNavigateToTools,
    )
}

/**
 * The pure-state half of [HomeScreen]: the three setup rows plus the navigation list, with the
 * system probes hoisted out by the caller. `internal` rather than `private` so
 * `UiScreenshotCapture` can render the configured (IME enabled, microphone granted) setup state -
 * under Robolectric there is no way to make the real `InputMethodManager` report our service as
 * enabled, and the unconfigured state is the least informative thing to show a store visitor.
 */
@Composable
internal fun HomeScreenContent(
    isImeEnabled: Boolean,
    hasMicPermission: Boolean,
    isModelReady: Boolean,
    onOpenImeSettings: () -> Unit,
    onRequestMicPermission: () -> Unit,
    onNavigateToModel: () -> Unit,
    onNavigateToInput: () -> Unit,
    onNavigateToSpeech: () -> Unit,
    onNavigateToTools: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.home_section_setup),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 1. IME enabled
        StatusRow(
            icon = if (isImeEnabled) MyIcons.Keyboard else MyIcons.Warning,
            isSatisfied = isImeEnabled,
            title = if (isImeEnabled) stringResource(R.string.home_ime_enabled)
            else stringResource(R.string.home_ime_not_enabled),
            subtitle = if (isImeEnabled) stringResource(R.string.home_ime_enabled_subtitle)
            else stringResource(R.string.home_ime_not_enabled_subtitle),
            actionLabel = stringResource(R.string.action_open_settings),
            action = if (!isImeEnabled) onOpenImeSettings else null,
        )

        // 2. Microphone permission
        StatusRow(
            icon = if (hasMicPermission) MyIcons.Mic else MyIcons.MicOff,
            isSatisfied = hasMicPermission,
            title = if (hasMicPermission) stringResource(R.string.home_mic_granted)
            else stringResource(R.string.home_mic_required),
            subtitle = if (hasMicPermission) stringResource(R.string.home_mic_granted_subtitle)
            else stringResource(R.string.home_mic_required_subtitle),
            actionLabel = stringResource(R.string.action_grant_permission),
            action = if (!hasMicPermission) onRequestMicPermission else null,
        )

        // 3. Model downloaded - always show an action so the model screen stays reachable
        StatusRow(
            icon = if (isModelReady) MyIcons.CheckCircle else MyIcons.CloudDownload,
            isSatisfied = isModelReady,
            title = if (isModelReady) stringResource(R.string.home_model_ready)
            else stringResource(R.string.home_model_not_downloaded),
            subtitle = if (isModelReady) stringResource(R.string.home_model_ready_subtitle)
            else stringResource(R.string.home_model_not_downloaded_subtitle),
            actionLabel = if (isModelReady) stringResource(R.string.action_manage)
            else stringResource(R.string.action_download),
            action = onNavigateToModel,
        )

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(4.dp))

        Text(
            text = stringResource(R.string.home_section_configuration),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ConfigurationButton(
            icon = MyIcons.Mic,
            label = stringResource(R.string.home_pref_input),
            onClick = onNavigateToInput,
        )
        ConfigurationButton(
            icon = MyIcons.Keyboard,
            label = stringResource(R.string.home_pref_speech),
            onClick = onNavigateToSpeech,
        )
        ConfigurationButton(
            icon = MyIcons.Settings,
            label = stringResource(R.string.home_pref_tools),
            onClick = onNavigateToTools,
        )
    }
}

/** Returns `true` if the Outspoke IME appears in the system's enabled-input-methods list. */
private fun isOutspokeImeEnabled(context: Context): Boolean {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    return imm.enabledInputMethodList.any { it.packageName == context.packageName }
}

/**
 * A tappable navigation row in the Configuration section of the home screen: leading
 * icon, row title, hairline card chrome. The whole row is the touch target, so it meets
 * the 48 dp minimum without extra padding games.
 */
@Composable
private fun ConfigurationButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = OutspokeShapes.card,
        color = LocalOutspokeSurfaceRoles.current.card,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
            Text(text = label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * One setup-status row on the home screen: leading icon in a tonal tile, title + subtitle,
 * and an optional trailing action pill.
 *
 * The tile stays a neutral `surfaceVariant` in both states; only the glyph inside carries
 * the status tint (success when satisfied, warning when the row still needs action), so
 * state always reads through the glyph shape as well as the colour.
 */
@Composable
private fun StatusRow(
    icon: ImageVector,
    isSatisfied: Boolean,
    title: String,
    subtitle: String,
    actionLabel: String,
    action: (() -> Unit)?,
) {
    val statusColors = LocalOutspokeStatusColors.current
    Surface(
        shape = OutspokeShapes.card,
        color = LocalOutspokeSurfaceRoles.current.card,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(OutspokeShapes.chip)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isSatisfied) statusColors.success else statusColors.warning,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            // fill = false: the trailing action keeps its intrinsic width instead of being
            // squeezed until its label wraps; long subtitles ellipsize first.
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (action != null) {
                Spacer(Modifier.width(10.dp))
                ActionPill(
                    label = actionLabel,
                    emphasised = !isSatisfied,
                    onClick = action,
                )
            }
        }
    }
}

/**
 * Trailing pill button inside a status row. The emphasised variant (primary fill) marks
 * "do this next"; the tonal one is a secondary affordance on an already-satisfied row.
 */
@Composable
private fun ActionPill(
    label: String,
    emphasised: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        shape = OutspokeShapes.chip,
        colors = if (emphasised) {
            ButtonDefaults.buttonColors()
        } else {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
    ) {
        // No soft wrap: inside a setup row the button shares a tight width with the row text,
        // and a wrapped label turned the pill into a two-line lozenge.
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Preview(showBackground = true, name = "Home · Nothing Set Up")
@Composable
private fun HomeScreenNothingSetupPreview() {
    OutspokeTheme {
        HomeScreenContent(
            isImeEnabled = false,
            hasMicPermission = false,
            isModelReady = false,
            onOpenImeSettings = {},
            onRequestMicPermission = {},
            onNavigateToModel = {},
            onNavigateToInput = {},
            onNavigateToSpeech = {},
            onNavigateToTools = {},
        )
    }
}

@Preview(showBackground = true, name = "Home · All Ready")
@Composable
private fun HomeScreenAllReadyPreview() {
    OutspokeTheme {
        HomeScreenContent(
            isImeEnabled = true,
            hasMicPermission = true,
            isModelReady = true,
            onOpenImeSettings = {},
            onRequestMicPermission = {},
            onNavigateToModel = {},
            onNavigateToInput = {},
            onNavigateToSpeech = {},
            onNavigateToTools = {},
        )
    }
}

@Preview(showBackground = true, name = "Home · Partial Setup (IME done, no mic, no model)")
@Composable
private fun HomeScreenPartialSetupPreview() {
    OutspokeTheme {
        HomeScreenContent(
            isImeEnabled = true,
            hasMicPermission = false,
            isModelReady = false,
            onOpenImeSettings = {},
            onRequestMicPermission = {},
            onNavigateToModel = {},
            onNavigateToInput = {},
            onNavigateToSpeech = {},
            onNavigateToTools = {},
        )
    }
}

@Preview(showBackground = true, name = "Status Row · Done (no action)")
@Composable
private fun StatusRowDonePreview() {
    OutspokeTheme {
        StatusRow(
            icon = MyIcons.CheckCircle,
            isSatisfied = true,
            title = "Keyboard enabled",
            subtitle = "Outspoke is in the keyboard list.",
            actionLabel = "Open Settings",
            action = null,
        )
    }
}

@Preview(showBackground = true, name = "Status Row · Pending (with action)")
@Composable
private fun StatusRowPendingPreview() {
    OutspokeTheme {
        StatusRow(
            icon = MyIcons.Warning,
            isSatisfied = false,
            title = "Keyboard not enabled",
            subtitle = "Add Outspoke in system keyboard settings.",
            actionLabel = "Open Settings",
            action = {},
        )
    }
}

