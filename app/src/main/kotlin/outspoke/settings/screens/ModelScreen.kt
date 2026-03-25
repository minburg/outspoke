package dev.brgr.outspoke.settings.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.brgr.outspoke.settings.model.DownloadSource
import dev.brgr.outspoke.settings.model.ModelId
import dev.brgr.outspoke.settings.model.ModelInfo
import dev.brgr.outspoke.settings.model.ModelRegistry
import dev.brgr.outspoke.settings.model.ModelState
import dev.brgr.outspoke.settings.model.ModelViewModel
import dev.brgr.outspoke.ui.theme.OutspokeTheme

/**
 * Displays the full model catalog — one card per registered model — and allows the user
 * to download, delete, and select the active speech recognition model.
 */
@Composable
fun ModelScreen(
    viewModel: ModelViewModel = viewModel(),
) {
    val modelStates   by viewModel.modelStates.collectAsState()
    val selectedModel by viewModel.selectedModelId.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.errorMessage.collect { message ->
            snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Long)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        ModelListContent(
            modelStates   = modelStates,
            selectedModel = selectedModel,
            modifier      = Modifier.padding(padding),
            onDownload    = { viewModel.startDownload(it) },
            onCancel      = { viewModel.cancelDownload(it) },
            onDelete      = { viewModel.deleteModel(it) },
            onRetry       = { viewModel.startDownload(it) },
            onSelect      = { viewModel.selectModel(it) },
        )
    }
}

// ---------------------------------------------------------------------------
// Stateless list content
// ---------------------------------------------------------------------------

@Composable
private fun ModelListContent(
    modelStates: Map<ModelId, ModelState>,
    selectedModel: ModelId?,
    onDownload: (ModelId) -> Unit,
    onCancel: (ModelId) -> Unit,
    onDelete: (ModelId) -> Unit,
    onRetry: (ModelId) -> Unit,
    onSelect: (ModelId) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "Speech Recognition Models",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Download a model and tap \"Set as Active\" to use it for transcription. " +
                       "Only one model is loaded at a time.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        items(ModelRegistry.all, key = { it.id.name }) { modelInfo ->
            val state      = modelStates[modelInfo.id] ?: ModelState.NotDownloaded
            val isSelected = selectedModel == modelInfo.id
            ModelCard(
                modelInfo  = modelInfo,
                state      = state,
                isSelected = isSelected,
                onDownload = { onDownload(modelInfo.id) },
                onCancel   = { onCancel(modelInfo.id) },
                onDelete   = { onDelete(modelInfo.id) },
                onRetry    = { onRetry(modelInfo.id) },
                onSelect   = { onSelect(modelInfo.id) },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Model card
// ---------------------------------------------------------------------------

@Composable
private fun ModelCard(
    modelInfo: ModelInfo,
    state: ModelState,
    isSelected: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
    onSelect: () -> Unit,
) {
    val borderColor = when {
        isSelected -> MaterialTheme.colorScheme.primary
        else       -> MaterialTheme.colorScheme.outlineVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        border   = BorderStroke(width = if (isSelected) 2.dp else 1.dp, color = borderColor),
        colors   = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            else
                MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Title row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text  = modelInfo.displayName,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text  = "~${modelInfo.approximateSizeMb} MB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isSelected) {
                    Icon(
                        imageVector  = Icons.Default.CheckCircle,
                        contentDescription = "Active model",
                        tint  = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            Text(
                text  = modelInfo.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // State-specific actions
            when (state) {
                is ModelState.NotDownloaded -> NotDownloadedActions(onDownload)
                is ModelState.Downloading   -> DownloadingActions(state.progressFraction, onCancel)
                is ModelState.Ready         -> ReadyActions(isSelected, onSelect, onDelete)
                is ModelState.Corrupted     -> CorruptedActions(onRetry)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Action composables
// ---------------------------------------------------------------------------

@Composable
private fun NotDownloadedActions(onDownload: () -> Unit) {
    Button(
        onClick  = onDownload,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Default.Download, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Download")
    }
}

@Composable
private fun DownloadingActions(progress: Float, onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
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
                text  = "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = Icons.Default.Sync,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        OutlinedButton(
            onClick  = onCancel,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Cancel") }
    }
}

@Composable
private fun ReadyActions(
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    var isDeleteDialogVisible by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!isSelected) {
            Button(
                onClick  = onSelect,
                modifier = Modifier.weight(1f),
            ) {
                Text("Set as Active")
            }
        } else {
            // Selected indicator — takes the place of the button
            Text(
                text     = "Active model",
                style    = MaterialTheme.typography.labelMedium,
                color    = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .weight(1f)
                    .align(Alignment.CenterVertically),
            )
        }
        OutlinedButton(
            onClick = { isDeleteDialogVisible = true },
            colors  = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Icon(Icons.Default.Delete, contentDescription = "Delete model")
        }
    }

    // Delete confirmation dialog
    if (isDeleteDialogVisible) {
        DeleteConfirmDialog(
            onConfirm = {
                onDelete()
                isDeleteDialogVisible = false
            },
            onDismiss = { isDeleteDialogVisible = false }
        )
    }
}

@Composable
private fun CorruptedActions(onRetry: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector  = Icons.Default.Error,
            contentDescription = null,
            tint  = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text  = "Download failed or corrupted",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = onRetry) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Retry")
        }
    }
}

// ---------------------------------------------------------------------------
// Delete confirmation dialog
// ---------------------------------------------------------------------------

@Composable
private fun DeleteConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm Deletion") },
        text  = { Text("Are you sure you want to delete this model? This action cannot be undone.") },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

// ---------------------------------------------------------------------------
// Preview sample data
// ---------------------------------------------------------------------------

private val previewModelSmall = ModelInfo(
    id                = ModelId.PARAKEET_V3,
    displayName       = "Parakeet-V3 (Default)",
    description       = "Fast and compact English on-device ASR. Recommended for most devices.",
    approximateSizeMb = 300,
    source            = DownloadSource.ZipArchive("https://example.com"),
    requiredFiles     = listOf("model.onnx"),
)

private val previewModelLarge = ModelInfo(
    id                = ModelId.WHISPER_SMALL,
    displayName       = "Whisper Large-v3 Turbo (INT8)",
    description       = "OpenAI Whisper Large-v3 with a turbo decoder. Multilingual, INT8 (~1.1 GB).",
    approximateSizeMb = 1_037,
    source            = DownloadSource.ZipArchive("https://example.com"),
    requiredFiles     = listOf("encoder.onnx", "decoder.onnx"),
)

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(showBackground = true, name = "Model Screen · Mixed States")
@Composable
private fun ModelListContentPreview() {
    OutspokeTheme {
        ModelListContent(
            modelStates = mapOf(
                ModelId.PARAKEET_V3  to ModelState.Ready,
                ModelId.WHISPER_SMALL to ModelState.NotDownloaded,
            ),
            selectedModel = ModelId.PARAKEET_V3,
            onDownload = {}, onCancel = {}, onDelete = {}, onRetry = {}, onSelect = {},
        )
    }
}

@Preview(showBackground = true, name = "Model Card · Not Downloaded")
@Composable
private fun ModelCardNotDownloadedPreview() {
    OutspokeTheme {
        ModelCard(
            modelInfo  = previewModelSmall,
            state      = ModelState.NotDownloaded,
            isSelected = false,
            onDownload = {}, onCancel = {}, onDelete = {}, onRetry = {}, onSelect = {},
        )
    }
}

@Preview(showBackground = true, name = "Model Card · Downloading 45%")
@Composable
private fun ModelCardDownloadingPreview() {
    OutspokeTheme {
        ModelCard(
            modelInfo  = previewModelLarge,
            state      = ModelState.Downloading(0.45f),
            isSelected = false,
            onDownload = {}, onCancel = {}, onDelete = {}, onRetry = {}, onSelect = {},
        )
    }
}

@Preview(showBackground = true, name = "Model Card · Ready (selected)")
@Composable
private fun ModelCardReadySelectedPreview() {
    OutspokeTheme {
        ModelCard(
            modelInfo  = previewModelSmall,
            state      = ModelState.Ready,
            isSelected = true,
            onDownload = {}, onCancel = {}, onDelete = {}, onRetry = {}, onSelect = {},
        )
    }
}

@Preview(showBackground = true, name = "Model Card · Ready (not selected)")
@Composable
private fun ModelCardReadyNotSelectedPreview() {
    OutspokeTheme {
        ModelCard(
            modelInfo  = previewModelLarge,
            state      = ModelState.Ready,
            isSelected = false,
            onDownload = {}, onCancel = {}, onDelete = {}, onRetry = {}, onSelect = {},
        )
    }
}

@Preview(showBackground = true, name = "Model Card · Corrupted")
@Composable
private fun ModelCardCorruptedPreview() {
    OutspokeTheme {
        ModelCard(
            modelInfo  = previewModelSmall,
            state      = ModelState.Corrupted,
            isSelected = false,
            onDownload = {}, onCancel = {}, onDelete = {}, onRetry = {}, onSelect = {},
        )
    }
}

@Preview(showBackground = true, name = "Action · Not Downloaded")
@Composable
private fun NotDownloadedActionsPreview() {
    OutspokeTheme { NotDownloadedActions(onDownload = {}) }
}

@Preview(showBackground = true, name = "Action · Downloading 70%")
@Composable
private fun DownloadingActionsPreview() {
    OutspokeTheme { DownloadingActions(progress = 0.7f, onCancel = {}) }
}

@Preview(showBackground = true, name = "Action · Ready (selected)")
@Composable
private fun ReadyActionsSelectedPreview() {
    OutspokeTheme { ReadyActions(isSelected = true, onSelect = {}, onDelete = {}) }
}

@Preview(showBackground = true, name = "Action · Ready (not selected)")
@Composable
private fun ReadyActionsNotSelectedPreview() {
    OutspokeTheme { ReadyActions(isSelected = false, onSelect = {}, onDelete = {}) }
}

@Preview(showBackground = true, name = "Action · Corrupted")
@Composable
private fun CorruptedActionsPreview() {
    OutspokeTheme { CorruptedActions(onRetry = {}) }
}

@Preview(showBackground = true, name = "Delete Confirm Dialog")
@Composable
private fun DeleteConfirmDialogPreview() {
    OutspokeTheme { DeleteConfirmDialog(onConfirm = {}, onDismiss = {}) }
}

