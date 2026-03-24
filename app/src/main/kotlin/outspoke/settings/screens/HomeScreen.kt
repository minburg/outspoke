package dev.brgr.outspoke.settings.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.brgr.outspoke.audio.PermissionHelper
import dev.brgr.outspoke.settings.model.ModelStorageManager

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
    }

    // Refresh all statuses on every ON_RESUME — catches changes made in other apps/settings.
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isImeEnabled = isOutspokeImeEnabled(context)
                hasMicPermission = PermissionHelper.hasRecordPermission(context)
                isModelReady = ModelStorageManager.isModelReady(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

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
            icon = if (isImeEnabled) Icons.Default.Keyboard else Icons.Default.Warning,
            iconTint = if (isImeEnabled) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.error,
            title = if (isImeEnabled) "Keyboard enabled" else "Keyboard not enabled",
            subtitle = if (isImeEnabled) "Outspoke is in the keyboard list."
                       else "Add Outspoke in system keyboard settings.",
            actionLabel = "Open Settings",
            action = if (!isImeEnabled) {
                { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
            } else null,
        )

        // 2. Microphone permission
        StatusRow(
            icon = if (hasMicPermission) Icons.Default.Mic else Icons.Default.MicOff,
            iconTint = if (hasMicPermission) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.error,
            title = if (hasMicPermission) "Microphone access granted"
                    else "Microphone permission required",
            subtitle = if (hasMicPermission) "Audio is processed entirely on-device."
                       else "Tap to grant microphone access.",
            actionLabel = "Grant Permission",
            action = if (!hasMicPermission) {
                { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
            } else null,
        )

        // 3. Model downloaded
        StatusRow(
            icon = if (isModelReady) Icons.Default.CheckCircle else Icons.Default.CloudDownload,
            iconTint = if (isModelReady) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.error,
            title = if (isModelReady) "Model ready" else "Model not downloaded",
            subtitle = if (isModelReady) "Parakeet-V3 is installed and ready."
                       else "Download the transcription model to use the keyboard.",
            actionLabel = "Download",
            action = if (!isModelReady) onNavigateToModel else null,
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
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("Keyboard Preferences")
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Returns `true` if the Outspoke IME appears in the system's enabled-input-methods list. */
private fun isOutspokeImeEnabled(context: Context): Boolean {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    return imm.enabledInputMethodList.any { it.packageName == context.packageName }
}

// ---------------------------------------------------------------------------
// Reusable status row card
// ---------------------------------------------------------------------------

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

