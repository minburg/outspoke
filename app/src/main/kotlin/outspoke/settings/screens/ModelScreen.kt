package dev.brgr.outspoke.settings.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.brgr.outspoke.settings.model.ModelState
import dev.brgr.outspoke.settings.model.ModelViewModel

/**
 * Shows the current [ModelState] and exposes download / delete actions.
 *
 * The ViewModel is scoped to the NavBackStackEntry so it survives screen rotations
 * but is cleaned up when the user navigates away.
 */
@Composable
fun ModelScreen(
    viewModel: ModelViewModel = viewModel(),
) {
    val modelState by viewModel.modelState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Collect one-shot error events and surface them as Snackbars.
    LaunchedEffect(viewModel) {
        viewModel.errorMessage.collect { message ->
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Long,
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when (val state = modelState) {
                is ModelState.NotDownloaded -> NotDownloadedContent(
                    onDownload = viewModel::startDownload,
                )
                is ModelState.Downloading -> DownloadingContent(
                    progress = state.progressFraction,
                    onCancel = viewModel::cancelDownload,
                )
                is ModelState.Ready -> ReadyContent(
                    onDelete = viewModel::deleteModel,
                )
                is ModelState.Corrupted -> CorruptedContent(
                    onRetry = viewModel::startDownload,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// State-specific content composables
// ---------------------------------------------------------------------------

@Composable
private fun NotDownloadedContent(onDownload: () -> Unit) {
    Spacer(Modifier.height(16.dp))
    Icon(
        imageVector = Icons.Default.CloudDownload,
        contentDescription = null,
        modifier = Modifier.size(72.dp),
        tint = MaterialTheme.colorScheme.primary,
    )
    Text(
        text = "Parakeet-V3 Model",
        style = MaterialTheme.typography.headlineSmall,
    )
    Text(
        text = "The on-device speech recognition model must be downloaded before you can use the keyboard.\n\n" +
               "4 files will be downloaded (encoder, decoder, config, vocab) — approx. 300 MB total.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Button(
        onClick = onDownload,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Default.Download, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Download Model")
    }
}

@Composable
private fun DownloadingContent(progress: Float, onCancel: () -> Unit) {
    Spacer(Modifier.height(16.dp))
    Icon(
        imageVector = Icons.Default.Sync,
        contentDescription = null,
        modifier = Modifier.size(72.dp),
        tint = MaterialTheme.colorScheme.primary,
    )
    Text(
        text = "Downloading…",
        style = MaterialTheme.typography.headlineSmall,
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Do not close the app",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    OutlinedButton(onClick = onCancel) { Text("Cancel") }
}

@Composable
private fun ReadyContent(onDelete: () -> Unit) {
    Spacer(Modifier.height(16.dp))
    Icon(
        imageVector = Icons.Default.CheckCircle,
        contentDescription = null,
        modifier = Modifier.size(72.dp),
        tint = MaterialTheme.colorScheme.primary,
    )
    Text(
        text = "Model Ready",
        style = MaterialTheme.typography.headlineSmall,
    )
    Text(
        text = "Parakeet-V3 is installed and ready for fully offline transcription.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    OutlinedButton(
        onClick = onDelete,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.error,
        ),
    ) {
        Icon(Icons.Default.Delete, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Delete Model")
    }
}

@Composable
private fun CorruptedContent(onRetry: () -> Unit) {
    Spacer(Modifier.height(16.dp))
    Icon(
        imageVector = Icons.Default.Error,
        contentDescription = null,
        modifier = Modifier.size(72.dp),
        tint = MaterialTheme.colorScheme.error,
    )
    Text(
        text = "Download Failed",
        style = MaterialTheme.typography.headlineSmall,
    )
    Text(
        text = "The downloaded file appears to be incomplete or corrupted — one or more required " +
               "model files are missing or failed integrity verification. " +
               "The partial download has been discarded. Please try again.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Button(onClick = onRetry) {
        Icon(Icons.Default.Refresh, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Retry Download")
    }
}

