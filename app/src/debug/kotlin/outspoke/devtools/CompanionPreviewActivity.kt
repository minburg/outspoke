package dev.brgr.outspoke.devtools

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.brgr.outspoke.R
import dev.brgr.outspoke.ui.theme.MyIcons
import dev.brgr.outspoke.settings.screens.HomeScreen
import dev.brgr.outspoke.settings.screens.InputPreferencesScreen
import dev.brgr.outspoke.settings.screens.MicCalibrationScreen
import dev.brgr.outspoke.settings.screens.ModelScreen
import dev.brgr.outspoke.settings.screens.SpeechPreferencesScreen
import dev.brgr.outspoke.settings.screens.ToolsPreferencesScreen
import dev.brgr.outspoke.ui.theme.OutspokeTheme

/**
 * Debug-only host for the REAL companion screens, selected by intent extra.
 *
 * Screens run against their real ViewModels and real on-device state (installed models,
 * stored preferences), but inside this activity's own Scaffold, so navigation callbacks are
 * no-ops. Purpose: review the shipped UI on a device without driving the whole navigation
 * graph through the touchscreen.
 *
 * Launch:
 * `adb shell am start -n dev.brgr.outspoke/dev.brgr.outspoke.devtools.CompanionPreviewActivity
 * --es screen home`
 * with `screen` in home | model | pref_input | pref_speech | tools | calibration.
 */
class CompanionPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val screen = intent.getStringExtra("screen") ?: "home"
        setContent { OutspokeTheme { CompanionPreviewScaffold(screen) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompanionPreviewScaffold(screen: String) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = screenTitle(screen)) },
                navigationIcon = {
                    IconButton(onClick = {}) {
                        Icon(
                            imageVector = MyIcons.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        androidx.compose.foundation.layout.Box(modifier = Modifier.padding(padding)) {
            when (screen) {
                "model" -> ModelScreen()
                "pref_input" -> InputPreferencesScreen(onNavigateToCalibration = {})
                "pref_speech" -> SpeechPreferencesScreen()
                "tools" -> ToolsPreferencesScreen()
                "calibration" -> MicCalibrationScreen()
                else -> HomeScreen(
                    onNavigateToModel = {},
                    onNavigateToInput = {},
                    onNavigateToSpeech = {},
                    onNavigateToTools = {},
                )
            }
        }
    }
}

@Composable
private fun screenTitle(screen: String): String = when (screen) {
    "model" -> stringResource(R.string.nav_title_model)
    "pref_input" -> stringResource(R.string.nav_title_input)
    "pref_speech" -> stringResource(R.string.nav_title_speech)
    "tools" -> stringResource(R.string.nav_title_tools)
    "calibration" -> stringResource(R.string.nav_title_calib)
    else -> stringResource(R.string.app_name)
}
