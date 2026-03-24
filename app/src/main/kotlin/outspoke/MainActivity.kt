package dev.brgr.outspoke

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.brgr.outspoke.ui.theme.OutspokeTheme

class MainActivity : ComponentActivity() {

    // Incremented every onResume so MicPermissionScreen can re-check whether the
    // permission was granted while the user was in the system Settings screen.
    private var resumeCount by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OutspokeTheme {
                MicPermissionScreen(resumeCount = resumeCount)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeCount++
    }
}

// ---------------------------------------------------------------------------
// Permission state machine
// ---------------------------------------------------------------------------

private enum class MicPermissionState {
    Checking,           // Waiting for the first system dialog result
    Granted,            // Permission held — keyboard is usable
    NeedsRequest,       // Denied once; system dialog can still be shown
    PermanentlyDenied,  // "Don't ask again" selected; must send user to Settings
}

@Composable
private fun MicPermissionScreen(resumeCount: Int) {
    val activity = LocalContext.current as ComponentActivity

    var permState by remember {
        val alreadyGranted = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        mutableStateOf(
            if (alreadyGranted) MicPermissionState.Granted else MicPermissionState.Checking
        )
    }

    val launcher = rememberLauncherForActivityResult(RequestPermission()) { granted ->
        permState = when {
            granted -> MicPermissionState.Granted
            activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) ->
                MicPermissionState.NeedsRequest
            else -> MicPermissionState.PermanentlyDenied
        }
    }

    // Show the system dialog automatically on first launch.
    LaunchedEffect(Unit) {
        if (permState == MicPermissionState.Checking) {
            launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Re-check every time the activity resumes — catches the case where the user
    // went to App Settings and granted the permission there.
    LaunchedEffect(resumeCount) {
        val nowGranted = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (nowGranted) permState = MicPermissionState.Granted
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (permState) {
                MicPermissionState.Checking -> CircularProgressIndicator()
                MicPermissionState.Granted -> GrantedContent()
                MicPermissionState.NeedsRequest -> RationaleContent(
                    onGrant = { launcher.launch(Manifest.permission.RECORD_AUDIO) },
                )
                MicPermissionState.PermanentlyDenied -> PermanentlyDeniedContent(
                    onOpenSettings = {
                        activity.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${activity.packageName}"),
                            )
                        )
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// State-specific content composables
// ---------------------------------------------------------------------------

@Composable
private fun GrantedContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "Microphone access granted",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Outspoke is ready to use.\n\nEnable it under Settings → General Management → Keyboard → On-screen keyboard.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RationaleContent(onGrant: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.MicOff,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Text(
            text = "Microphone permission required",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Outspoke transcribes your speech entirely on-device and never sends audio to a server. Microphone access is required for the keyboard to work.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onGrant) {
            Text("Grant microphone access")
        }
    }
}

@Composable
private fun PermanentlyDeniedContent(onOpenSettings: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.MicOff,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Text(
            text = "Permission permanently denied",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Microphone access was blocked permanently. Open App Settings, then enable the Microphone permission and return here.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onOpenSettings) {
            Text("Open App Settings")
        }
    }
}
