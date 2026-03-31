package dev.brgr.outspoke.settings.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.brgr.outspoke.audio.PermissionHelper
import dev.brgr.outspoke.inference.InferenceService
import dev.brgr.outspoke.settings.model.ModelRegistry
import dev.brgr.outspoke.settings.model.ModelStorageManager
import dev.brgr.outspoke.ui.theme.MyIcons
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
    onNavigateToPreferences: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    var isImeEnabled by remember { mutableStateOf(false) }
    var hasMicPermission by remember { mutableStateOf(false) }
    var isModelReady by remember { mutableStateOf(false) }

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
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
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
        onNavigateToPreferences = onNavigateToPreferences,
    )
}

@Composable
private fun HomeScreenContent(
    isImeEnabled: Boolean,
    hasMicPermission: Boolean,
    isModelReady: Boolean,
    onOpenImeSettings: () -> Unit,
    onRequestMicPermission: () -> Unit,
    onNavigateToModel: () -> Unit,
    onNavigateToPreferences: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Setup",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 1. IME enabled
        StatusRow(
            icon = if (isImeEnabled) MyIcons.Keyboard else MyIcons.Warning,
            iconTint = if (isImeEnabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error,
            title = if (isImeEnabled) "Keyboard enabled" else "Keyboard not enabled",
            subtitle = if (isImeEnabled) "Outspoke is in the keyboard list."
            else "Add Outspoke in system keyboard settings.",
            actionLabel = "Open Settings",
            action = if (!isImeEnabled) onOpenImeSettings else null,
        )

        // 2. Microphone permission
        StatusRow(
            icon = if (hasMicPermission) MyIcons.Mic else MyIcons.MicOff,
            iconTint = if (hasMicPermission) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error,
            title = if (hasMicPermission) "Microphone access granted"
            else "Microphone permission required",
            subtitle = if (hasMicPermission) "Audio is processed entirely on-device."
            else "Tap to grant microphone access.",
            actionLabel = "Grant Permission",
            action = if (!hasMicPermission) onRequestMicPermission else null,
        )

        // 3. Model downloaded - always show an action so the model screen stays reachable
        StatusRow(
            icon = if (isModelReady) MyIcons.CheckCircle else MyIcons.CloudDownload,
            iconTint = if (isModelReady) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error,
            title = if (isModelReady) "Model ready" else "Model not downloaded",
            subtitle = if (isModelReady) "A transcription model is installed and ready."
            else "Download a transcription model to use the keyboard.",
            actionLabel = if (isModelReady) "Manage" else "Download",
            action = onNavigateToModel,
        )

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(4.dp))

        Text(
            text = "Configuration",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedButton(
            onClick = onNavigateToPreferences,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = MyIcons.Settings,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("Keyboard Preferences")
        }
    }
}

/** Returns `true` if the Outspoke IME appears in the system's enabled-input-methods list. */
private fun isOutspokeImeEnabled(context: Context): Boolean {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    return imm.enabledInputMethodList.any { it.packageName == context.packageName }
}

@Composable
private fun StatusRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    actionLabel: String,
    action: (() -> Unit)?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (action != null) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = action) { Text(actionLabel) }
            }
        }
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
            onNavigateToPreferences = {},
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
            onNavigateToPreferences = {},
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
            onNavigateToPreferences = {},
        )
    }
}

@Preview(showBackground = true, name = "Status Row · Done (no action)")
@Composable
private fun StatusRowDonePreview() {
    OutspokeTheme {
        StatusRow(
            icon = MyIcons.CheckCircle,
            iconTint = Color.Unspecified,
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
            iconTint = Color.Unspecified,
            title = "Keyboard not enabled",
            subtitle = "Add Outspoke in system keyboard settings.",
            actionLabel = "Open Settings",
            action = {},
        )
    }
}

