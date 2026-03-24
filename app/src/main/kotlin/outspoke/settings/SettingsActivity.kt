package dev.brgr.outspoke.settings

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.brgr.outspoke.inference.InferenceService
import dev.brgr.outspoke.settings.model.ModelStorageManager
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import dev.brgr.outspoke.settings.screens.HomeScreen
import dev.brgr.outspoke.settings.screens.ModelScreen
import dev.brgr.outspoke.settings.screens.PreferencesScreen
import dev.brgr.outspoke.ui.theme.OutspokeTheme

/** Entry-point for the Outspoke companion / settings app (the launcher icon). */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Only start the inference foreground service if the model is already on-device.
        // If the user opens Settings purely to download the model, we skip the service start
        // (and the brief foreground notification flash that would otherwise appear).
        // The IME will start the service once the download completes via FileObserver.
        if (ModelStorageManager.isModelReady(this)) {
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

// ---------------------------------------------------------------------------
// Route constants
// ---------------------------------------------------------------------------

object SettingsRoutes {
    const val HOME = "home"
    const val MODEL = "model"
    const val PREFERENCES = "preferences"
}

// ---------------------------------------------------------------------------
// NavHost
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsNavHost(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val title = when (currentRoute) {
        SettingsRoutes.HOME -> "Outspoke"
        SettingsRoutes.MODEL -> "Download Model"
        SettingsRoutes.PREFERENCES -> "Preferences"
        else -> "Outspoke"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (currentRoute != SettingsRoutes.HOME) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
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
                    onNavigateToPreferences = { navController.navigate(SettingsRoutes.PREFERENCES) },
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
            composable(route = SettingsRoutes.PREFERENCES) {
                PreferencesScreen()
            }
        }
    }
}

