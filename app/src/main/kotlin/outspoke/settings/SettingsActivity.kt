package dev.brgr.outspoke.settings

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import dev.brgr.outspoke.R
import dev.brgr.outspoke.audio.PermissionHelper
import dev.brgr.outspoke.inference.InferenceService
import dev.brgr.outspoke.settings.model.ModelStorageManager
import dev.brgr.outspoke.settings.screens.HomeScreen
import dev.brgr.outspoke.settings.screens.InputPreferencesScreen
import dev.brgr.outspoke.settings.screens.MicCalibrationScreen
import dev.brgr.outspoke.settings.screens.ModelScreen
import dev.brgr.outspoke.settings.screens.SpeechPreferencesScreen
import dev.brgr.outspoke.settings.screens.ToolsPreferencesScreen
import dev.brgr.outspoke.ui.theme.MyIcons
import dev.brgr.outspoke.ui.theme.OutspokeTheme

/** Entry-point for the Outspoke companion / settings app (the launcher icon). */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ModelStorageManager.isModelReady(this) && PermissionHelper.hasRecordPermission(this)) {
            startForegroundService(Intent(this, InferenceService::class.java))
        }

        enableEdgeToEdge()
        setContent {
            OutspokeTheme {
                val navController = rememberNavController()
                SettingsNavHost(navController = navController)
            }
        }
    }
}

object SettingsRoutes {
    const val HOME = "home"
    const val MODEL = "model"
    const val PREF_INPUT = "pref_input"
    const val PREF_SPEECH = "pref_speech"
    const val PREF_TOOLS = "pref_tools"
    const val CALIBRATION = "calibration"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsNavHost(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val title = when (currentRoute) {
        SettingsRoutes.HOME -> stringResource(R.string.app_name)
        SettingsRoutes.MODEL -> stringResource(R.string.nav_title_model)
        SettingsRoutes.PREF_INPUT -> stringResource(R.string.nav_title_input)
        SettingsRoutes.PREF_SPEECH -> stringResource(R.string.nav_title_speech)
        SettingsRoutes.PREF_TOOLS -> stringResource(R.string.nav_title_tools)
        SettingsRoutes.CALIBRATION -> stringResource(R.string.nav_title_calib)
        else -> stringResource(R.string.app_name)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (currentRoute != SettingsRoutes.HOME) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(
                                imageVector = MyIcons.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = SettingsRoutes.HOME,
            modifier = Modifier.padding(padding),
        ) {
            composable(
                route = SettingsRoutes.HOME,
                // Handle deep-links from the keyboard (e.g. "Open Outspoke" buttons)
                deepLinks = listOf(
                    navDeepLink { uriPattern = "outspoke://settings/home" },
                    navDeepLink { uriPattern = "outspoke://settings/permissions" },
                ),
            ) {
                HomeScreen(
                    onNavigateToModel = { navController.navigate(SettingsRoutes.MODEL) },
                    onNavigateToInput = { navController.navigate(SettingsRoutes.PREF_INPUT) },
                    onNavigateToSpeech = { navController.navigate(SettingsRoutes.PREF_SPEECH) },
                    onNavigateToTools = { navController.navigate(SettingsRoutes.PREF_TOOLS) },
                )
            }
            composable(
                route = SettingsRoutes.MODEL,
                deepLinks = listOf(
                    navDeepLink { uriPattern = "outspoke://settings/model" },
                ),
            ) {
                ModelScreen()
            }
            composable(route = SettingsRoutes.PREF_INPUT) {
                InputPreferencesScreen(
                    onNavigateToCalibration = { navController.navigate(SettingsRoutes.CALIBRATION) },
                )
            }
            composable(route = SettingsRoutes.PREF_SPEECH) {
                SpeechPreferencesScreen()
            }
            composable(route = SettingsRoutes.PREF_TOOLS) {
                ToolsPreferencesScreen()
            }
            composable(route = SettingsRoutes.CALIBRATION) {
                MicCalibrationScreen()
            }
        }
    }
}

/** Shows the Home screen inside the full nav scaffold (top bar + nav structure). */
@Preview(showBackground = true, name = "Settings · Home")
@Composable
private fun SettingsNavHostPreview() {
    OutspokeTheme {
        SettingsNavHost(navController = rememberNavController())
    }
}

