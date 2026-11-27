package dev.brgr.outspoke.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.brgr.outspoke.R
import dev.brgr.outspoke.ime.EnterAction
import dev.brgr.outspoke.settings.screens.HomeScreen
import dev.brgr.outspoke.settings.screens.InputPreferencesScreen
import dev.brgr.outspoke.settings.screens.ModelScreen
import dev.brgr.outspoke.ui.keyboard.KeyboardScreen
import dev.brgr.outspoke.ui.keyboard.KeyboardUiState
import dev.brgr.outspoke.ui.keyboard.components.SUGGESTION_BAR_HEIGHT_DP
import dev.brgr.outspoke.ui.theme.MyIcons
import dev.brgr.outspoke.ui.theme.OutspokeKeyboardTheme
import dev.brgr.outspoke.ui.theme.OutspokeTheme
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the shipped screens headlessly and writes them out as PNGs, so store screenshots and a
 * visual diff of the real UI are possible without a device, a granted microphone or a downloaded
 * model. Real composables, real themes - the only invention is the mock host app the keyboard
 * strip is shown over (a real screenshot of an IME always has some other app behind it).
 *
 * Opt-in: every test is skipped unless `OUTSPOKE_SHOT_DIR` points at the output directory, so the
 * normal unit-test run neither renders nor writes anything.
 *
 * ```
 * OUTSPOKE_SHOT_DIR=/tmp/shots ./gradlew testDebugUnitTest --tests '*UiScreenshotCapture*'
 * ```
 *
 * Robolectric's NATIVE graphics mode (real minikin + skia) is what makes the capture meaningful;
 * under the default legacy mode text would not be shaped. `xxhdpi` over a 411 dp phone width puts
 * the output at 1233 px wide, above the store minimum of 1080. Dark renders come from the `night`
 * resource qualifier rather than a theme argument, because `OutspokeKeyboardTheme` picks its
 * palette from `isSystemInDarkTheme()` exactly like the IME window does.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = UiScreenshotCapture.LIGHT)
@RunWith(AndroidJUnit4::class)
class UiScreenshotCapture {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun homeLight() = capture("01-home-light") { CompanionScreen("home") }

    @Test
    fun modelLight() = capture("02-model-light") { CompanionScreen("model") }

    @Test
    fun inputPreferencesLight() = capture("03-preferences-input-light") { CompanionScreen("pref_input") }

    @Test
    fun keyboardIdleLight() = capture("04-keyboard-idle-light") {
        HostAppThenKeyboard(KeyboardUiState.Idle, 0f)
    }

    @Test
    fun keyboardListeningLight() = capture("05-keyboard-listening-light") {
        HostAppThenKeyboard(KeyboardUiState.Processing("the rotation fix needs a window"), 0.55f)
    }

    @Test
    fun keyboardSuggestionsLight() = capture("06-keyboard-suggestions-light") {
        HostAppThenKeyboard(
            KeyboardUiState.Idle,
            0f,
            wordSuggestions = listOf("Parakeet", "parakeet", "Parraket", "parakeets", "Paraket"),
        )
    }

    @Config(sdk = [34], qualifiers = DARK)
    @Test
    fun homeDark() = capture("07-home-dark") { CompanionScreen("home") }

    @Config(sdk = [34], qualifiers = DARK)
    @Test
    fun keyboardListeningDark() = capture("08-keyboard-listening-dark") {
        HostAppThenKeyboard(KeyboardUiState.Listening, 0.62f)
    }

    /** Dark strip with a long in-flight partial: the source crop for the store feature graphic. */
    @Config(sdk = [34], qualifiers = DARK)
    @Test
    fun keyboardProcessingDark() = capture("09-keyboard-processing-dark") {
        HostAppThenKeyboard(KeyboardUiState.Processing("This is a short example of recording speech and"), 0.62f)
    }

    /**
     * The store feature graphic's frame filled by the strip itself: a landscape IME window at
     * 1024 x 500 px, so the asset is a real render rather than a crop of a portrait one.
     */
    @Config(sdk = [34], qualifiers = FEATURE)
    @Test
    fun featureGraphicDark() = capture("10-feature-graphic-dark") {
        KeyboardStrip(
            KeyboardUiState.Processing("This is a short example of recording speech and"),
            0.62f,
            emptyList(),
            stripHeightDp = 250,
        )
    }

    private fun capture(name: String, content: @Composable () -> Unit) {
        // Blank counts as unset: an exported empty OUTSPOKE_SHOT_DIR must skip, not write into "".
        val dir = System.getenv("OUTSPOKE_SHOT_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("set OUTSPOKE_SHOT_DIR to write screenshots", dir != null)
        val outDir = requireNotNull(dir)
        compose.activity.setContent { content() }
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        outDir.mkdirs()
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    companion object {
        /** 411 x 891 dp at xxhdpi: a real phone viewport, 1233 x 2673 px. */
        const val LIGHT = "w411dp-h891dp-xxhdpi"

        /**
         * Robolectric's qualifier parser walks the tokens in the order Android documents them, so
         * `night` belongs *before* the density token - `...-xxhdpi-night` is rejected as an
         * unrecognised trailing segment.
         */
        const val DARK = "w411dp-h891dp-night-xxhdpi"

        /** Landscape strip: 512 x 250 dp at xhdpi is exactly the 1024 x 500 feature-graphic frame. */
        const val FEATURE = "w512dp-h250dp-land-night-xhdpi"

        /** Production strip height: 20 % of a 1080 p phone's usable height, bar slot excluded. */
        const val STRIP_HEIGHT_DP = 160
    }
}

/** The real companion screens, in the same Scaffold + top-bar shape `SettingsActivity` gives them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompanionScreen(screen: String) {
    OutspokeTheme {
        // No scroller here: each screen scrolls itself, and wrapping a LazyColumn in a
        // verticalScroll Column is an infinite-height measurement error, not a layout.
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(screenTitle(screen)) },
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
            Box(modifier = Modifier.padding(padding)) {
                when (screen) {
                    "model" -> ModelScreen()
                    "pref_input" -> InputPreferencesScreen(onNavigateToCalibration = {})
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
}

@Composable
private fun screenTitle(screen: String): String = when (screen) {
    "model" -> stringResource(R.string.nav_title_model)
    "pref_input" -> stringResource(R.string.nav_title_input)
    else -> stringResource(R.string.app_name)
}

/**
 * The IME strip at its production height (20 % of the usable screen) over a mock host app, which is
 * the only honest way to show a keyboard: on a device there is always a text field behind it.
 */
@Composable
private fun HostAppThenKeyboard(
    state: KeyboardUiState,
    amplitude: Float,
    wordSuggestions: List<String> = emptyList(),
) {
    OutspokeTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxWidth().weight(1f).padding(20.dp)) {
                    Text("Stand-up notes", style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Ship 0.4.1 once the rotation fix is verified on a phone: the strip has to " +
                            "re-measure itself after a configuration change instead of keeping the " +
                            "height it was first laid out with.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    OutlinedTextField(
                        value = "Dictated: the wave form reacts ",
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Notes") },
                    )
                }
                KeyboardStrip(state, amplitude, wordSuggestions)
            }
        }
    }
}

@Composable
private fun KeyboardStrip(
    state: KeyboardUiState,
    amplitude: Float,
    wordSuggestions: List<String>,
    stripHeightDp: Int = UiScreenshotCapture.STRIP_HEIGHT_DP,
) {
    // The suggestion bar reserves a slot above the strip, so the window grows by exactly that
    // amount - the same arithmetic the IME service does with its measured bar-slot height.
    val barHeight = if (wordSuggestions.isEmpty()) 0 else SUGGESTION_BAR_HEIGHT_DP
    OutspokeKeyboardTheme {
        Box(modifier = Modifier.fillMaxWidth().height((stripHeightDp + barHeight).dp)) {
            KeyboardScreen(
                uiState = state,
                amplitude = amplitude,
                isContinuous = false,
                triggerMode = "HOLD",
                onRecordStart = {},
                onRecordStop = {},
                onContinuousModeEnabled = {},
                onRetry = {},
                onDeleteChar = {},
                onDeleteWord = {},
                onDeleteAll = {},
                onEnterAction = {},
                enterAction = EnterAction.NEWLINE,
                onSwitchKeyboard = {},
                onOpenCompanionApp = {},
                previewForceLockHint = false,
                wordSuggestions = wordSuggestions,
                suggestionBarDismissed = false,
            )
        }
    }
}
