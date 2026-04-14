package dev.brgr.outspoke.settings.screens

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.brgr.outspoke.R
import dev.brgr.outspoke.settings.model.*
import dev.brgr.outspoke.ui.theme.MyIcons
import dev.brgr.outspoke.ui.theme.OutspokeTheme

/**
 * Displays the full model catalog - one card per registered model - and allows the user
 * to download, delete, and select the active speech recognition model.
 */
@Composable
fun ModelScreen(
    viewModel: ModelViewModel = viewModel(),
) {
    val modelStates by viewModel.modelStates.collectAsState()
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
            modelStates = modelStates,
            selectedModel = selectedModel,
            modifier = Modifier.padding(padding),
            onDownload = { viewModel.startDownload(it) },
            onCancel = { viewModel.cancelDownload(it) },
            onDelete = { viewModel.deleteModel(it) },
            onRetry = { viewModel.startDownload(it) },
            onSelect = { viewModel.selectModel(it) },
        )
    }
}

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
                text = stringResource(R.string.model_screen_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.model_screen_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        items(ModelRegistry.all, key = { it.id.name }) { modelInfo ->
            val state = modelStates[modelInfo.id] ?: ModelState.NotDownloaded
            val isSelected = selectedModel == modelInfo.id
            ModelCard(
                modelInfo = modelInfo,
                state = state,
                isSelected = isSelected,
                onDownload = { onDownload(modelInfo.id) },
                onCancel = { onCancel(modelInfo.id) },
                onDelete = { onDelete(modelInfo.id) },
                onRetry = { onRetry(modelInfo.id) },
                onSelect = { onSelect(modelInfo.id) },
            )
        }
    }
}

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
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(width = if (isSelected) 2.dp else 1.dp, color = borderColor),
        colors = CardDefaults.cardColors(
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
                        text = modelInfo.displayName,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.model_size_format, modelInfo.approximateSizeMb),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isSelected) {
                    Icon(
                        imageVector = MyIcons.CheckCircle,
                        contentDescription = stringResource(R.string.cd_active_model),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            Text(
                text = modelInfo.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // State-specific actions
            when (state) {
                is ModelState.NotDownloaded -> NotDownloadedActions(modelInfo, onDownload)
                is ModelState.Downloading -> DownloadingActions(state.progressFraction, onCancel)
                is ModelState.Ready -> ReadyActions(isSelected, onSelect, onDelete)
                is ModelState.Corrupted -> CorruptedActions(onRetry)
            }
        }
    }
}

@Composable
private fun NotDownloadedActions(modelInfo: ModelInfo, onDownload: () -> Unit) {
    val context = LocalContext.current
    var showMobileDataDialog by remember { mutableStateOf(false) }

    Button(
        onClick = {
            if (context.isOnMobileData()) {
                showMobileDataDialog = true
            } else {
                onDownload()
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(MyIcons.Download, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.action_download))
    }

    if (showMobileDataDialog) {
        MobileDataWarningDialog(
            sizeMb = modelInfo.approximateSizeMb,
            onConfirm = {
                showMobileDataDialog = false
                onDownload()
            },
            onDismiss = { showMobileDataDialog = false },
        )
    }
}

/** Returns `true` when the device is connected via cellular but not Wi-Fi. */
private fun Context.isOnMobileData(): Boolean {
    val cm = getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
}

@Composable
private fun MobileDataWarningDialog(
    sizeMb: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = MyIcons.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text(stringResource(R.string.dialog_mobile_data_title)) },
        text = {
            Text(stringResource(R.string.dialog_mobile_data_message, sizeMb))
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text(stringResource(R.string.action_download_anyway)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
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
                text = "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = MyIcons.Sync,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.action_cancel)) }
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
                onClick = onSelect,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.action_set_as_active))
            }
        } else {
            Text(
                text = stringResource(R.string.model_active_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .weight(1f)
                    .align(Alignment.CenterVertically),
            )
        }
        OutlinedButton(
            onClick = { isDeleteDialogVisible = true },
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Icon(MyIcons.Delete, contentDescription = stringResource(R.string.cd_delete_model))
        }
    }

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
            imageVector = MyIcons.Error,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = stringResource(R.string.model_download_failed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = onRetry) {
            Icon(MyIcons.Refresh, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.action_retry))
        }
    }
}

@Composable
private fun DeleteConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_delete_title)) },
        text = { Text(stringResource(R.string.dialog_delete_message)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(R.string.action_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

private val previewModelSmall = ModelInfo(
    id = ModelId.PARAKEET_V3,
    displayName = "Parakeet-V3 (Default)",
    description = "Fast and compact English on-device ASR. Recommended for most devices.",
    approximateSizeMb = 700,
    source = DownloadSource.ZipArchive("https://example.com"),
    requiredFiles = listOf("model.onnx"),
)

private val previewModelLarge = ModelInfo(
    id = ModelId.WHISPER_SMALL,
    displayName = "Whisper Large-v3 Turbo (INT8)",
    description = "OpenAI Whisper Large-v3 with a turbo decoder. Multilingual, INT8 (~1.1 GB).",
    approximateSizeMb = 1_037,
    source = DownloadSource.ZipArchive("https://example.com"),
    requiredFiles = listOf("encoder.onnx", "decoder.onnx"),
)

@Preview(showBackground = true, name = "Model Screen · Mixed States")
@Composable
private fun ModelListContentPreview() {
    OutspokeTheme {
        ModelListContent(
            modelStates = mapOf(
                ModelId.PARAKEET_V3 to ModelState.Ready,
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
            modelInfo = previewModelSmall,
            state = ModelState.NotDownloaded,
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
            modelInfo = previewModelLarge,
            state = ModelState.Downloading(0.45f),
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
            modelInfo = previewModelSmall,
            state = ModelState.Ready,
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
            modelInfo = previewModelLarge,
            state = ModelState.Ready,
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
            modelInfo = previewModelSmall,
            state = ModelState.Corrupted,
            isSelected = false,
            onDownload = {}, onCancel = {}, onDelete = {}, onRetry = {}, onSelect = {},
        )
    }
}

@Preview(showBackground = true, name = "Action · Not Downloaded")
@Composable
private fun NotDownloadedActionsPreview() {
    OutspokeTheme { NotDownloadedActions(modelInfo = previewModelSmall, onDownload = {}) }
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

