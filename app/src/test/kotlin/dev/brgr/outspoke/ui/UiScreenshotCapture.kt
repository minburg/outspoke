package dev.brgr.outspoke.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.brgr.outspoke.R
import dev.brgr.outspoke.ime.EnterAction
import dev.brgr.outspoke.settings.model.ModelId
import dev.brgr.outspoke.settings.model.ModelRegistry
import dev.brgr.outspoke.settings.model.ModelState
import dev.brgr.outspoke.settings.screens.HomeScreenContent
import dev.brgr.outspoke.settings.screens.InputPreferencesScreen
import dev.brgr.outspoke.settings.screens.ModelListContent
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
 * Renders the shipped screens headlessly and writes them out as PNGs, so the store listing can be
 * refreshed without a device, a granted microphone or a downloaded model. Real composables, real
 * themes, real model registry - nothing is drawn by hand.
 *
 * `01`-`05` are the store set, in the order the listing shows them: the keyboard doing its job
 * inside someone's e-mail, the setup dashboard, the model list with nothing installed, the same
 * list with the active model installed, then the input preferences. Dark, because that is how the
 * listing reads. The `NN` prefix is the listing slot, so `NN-*.png` ships as `NN.jpg` under
 * `fastlane/metadata/android/en-US/images/phoneScreenshots/`. `06`-`10` are the same screens in
 * light plus two extra strip states for documentation, and `11` fills the store feature-graphic
 * frame with the strip itself.
 *
 * The keyboard shots are the ones a visitor decides on, so the dictated sentence is deliberately
 * ordinary e-mail prose and the strip agrees with the field above it: `TextInjector` commits
 * everything except the last six words, so exactly that tail is highlighted in the field, and the
 * status pill carries the utterance the strip is still decoding.
 *
 * Opt-in: every case is skipped unless `OUTSPOKE_SHOT_DIR` names the output directory (blank
 * counts as unset), so `./gradlew test` neither renders nor writes anything.
 *
 * ```
 * OUTSPOKE_SHOT_DIR=/tmp/shots ./gradlew testDebugUnitTest --tests '*UiScreenshotCapture*'
 * ```
 *
 * Robolectric's NATIVE graphics mode (real minikin + skia) is what makes a capture worth looking
 * at; under the legacy mode text is not shaped. `xxhdpi` over a 360 dp phone width gives exactly
 * the 1080 x 2376 px the store listing already uses, so the PNGs need no scaling. Dark comes from
 * the `night` resource qualifier rather than a theme argument, because `OutspokeKeyboardTheme`
 * picks its palette from `isSystemInDarkTheme()` exactly like the IME window does.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = UiScreenshotCapture.LIGHT)
@RunWith(AndroidJUnit4::class)
class UiScreenshotCapture {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // --- store set (dark): the keyboard at work first, then the screens behind it --------------

    @Config(sdk = [34], qualifiers = DARK)
    @Test
    fun dictatingDark() = capture("01-dictating-dark") {
        HostAppThenKeyboard(KeyboardUiState.Processing(DICTATED_UTTERANCE), 0.62f)
    }

    @Config(sdk = [34], qualifiers = DARK)
    @Test
    fun homeSetupDark() = capture("02-home-setup-dark") {
        CompanionScaffold(R.string.app_name) {
            // Configured state: the IME is enabled and the microphone granted, the model still
            // missing - the one thing the visitor has left to do, with its Download action visible.
            HomeScreenContent(
                isImeEnabled = true,
                hasMicPermission = true,
                isModelReady = false,
                onOpenImeSettings = {},
                onRequestMicPermission = {},
                onNavigateToModel = {},
                onNavigateToInput = {},
                onNavigateToSpeech = {},
                onNavigateToTools = {},
            )
        }
    }

    @Config(sdk = [34], qualifiers = DARK)
    @Test
    fun modelListDark() = capture("03-model-list-dark") {
        CompanionScaffold(R.string.nav_title_model) { ModelList(modelStatesWith(defaultReady = false)) }
    }

    @Config(sdk = [34], qualifiers = DARK)
    @Test
    fun modelActiveDark() = capture("04-model-active-dark") {
        CompanionScaffold(R.string.nav_title_model) { ModelList(modelStatesWith(defaultReady = true)) }
    }

    @Config(sdk = [34], qualifiers = DARK)
    @Test
    fun preferencesDark() = capture("05-preferences-dark") {
        CompanionScaffold(R.string.nav_title_input) { InputPreferencesScreen(onNavigateToCalibration = {}) }
    }

    // --- the same screens in light, plus the strip states the store set does not show ---------

    @Test
    fun homeSetupLight() = capture("06-home-setup-light") {
        CompanionScaffold(R.string.app_name) {
            HomeScreenContent(
                isImeEnabled = true,
                hasMicPermission = true,
                isModelReady = false,
                onOpenImeSettings = {},
                onRequestMicPermission = {},
                onNavigateToModel = {},
                onNavigateToInput = {},
                onNavigateToSpeech = {},
                onNavigateToTools = {},
            )
        }
    }

    @Test
    fun modelActiveLight() = capture("07-model-active-light") {
        CompanionScaffold(R.string.nav_title_model) { ModelList(modelStatesWith(defaultReady = true)) }
    }

    @Test
    fun preferencesLight() = capture("08-preferences-light") {
        CompanionScaffold(R.string.nav_title_input) { InputPreferencesScreen(onNavigateToCalibration = {}) }
    }

    @Test
    fun dictatedLight() = capture("09-dictated-light") {
        // Utterance finished: nothing composing, caret after the final full stop.
        HostAppThenKeyboard(KeyboardUiState.Idle, 0f)
    }

    @Test
    fun wordSuggestionLight() = capture("10-word-suggestion-light") {
        HostAppThenKeyboard(
            KeyboardUiState.Idle,
            0f,
            wordSuggestions = SUGGESTIONS_FOR_TAIL_WORD,
        )
    }

    /**
     * The store feature graphic's frame filled by the strip itself: a landscape IME window at
     * 1024 x 500 px, so the asset is a real render rather than a crop of a portrait one.
     */
    @Config(sdk = [34], qualifiers = FEATURE)
    @Test
    fun featureGraphicDark() = capture("11-feature-graphic-dark") {
        KeyboardStrip(KeyboardUiState.Processing(DICTATED_UTTERANCE), 0.62f, emptyList(), stripHeightDp = 250)
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

    /**
     * Model-list states for the two listing shots: nothing installed, or the default model
     * installed. Injected rather than read from disk - the storage manager's check is
     * "every required file exists and is non-empty", and Robolectric shares one app-data
     * directory between tests, so writing files would make shot 02 depend on test order.
     */
    private fun modelStatesWith(defaultReady: Boolean): Map<ModelId, ModelState> =
        ModelRegistry.all.associate { info ->
            info.id to if (defaultReady && info.id == ModelId.DEFAULT) {
                ModelState.Ready
            } else {
                ModelState.NotDownloaded
            }
        }

    /** The catalog rendered straight from [modelStatesWith]; the callbacks only matter on device. */
    @Composable
    private fun ModelList(modelStates: Map<ModelId, ModelState>) {
        ModelListContent(
            modelStates = modelStates,
            selectedModel = ModelId.DEFAULT,
            onDownload = {},
            onCancel = {},
            onDelete = {},
            onRetry = {},
            onSelect = {},
        )
    }

    companion object {
        /** 360 x 792 dp at xxhdpi: exactly the 1080 x 2376 px canvas the store listing uses. */
        const val LIGHT = "w360dp-h792dp-xxhdpi"

        /**
         * Robolectric's qualifier parser walks the tokens in the order Android documents them, so
         * `night` belongs *before* the density token - `...-xxhdpi-night` is rejected as an
         * unrecognised trailing segment.
         */
        const val DARK = "w360dp-h792dp-night-xxhdpi"

        /** Landscape strip: 512 x 250 dp at xhdpi is exactly the 1024 x 500 feature-graphic frame. */
        const val FEATURE = "w512dp-h250dp-land-night-xhdpi"

        /** Production strip height: 20 % of a 1080 p phone's usable height, bar slot excluded. */
        const val STRIP_HEIGHT_DP = 160

        /** The dictation every keyboard shot shows: ordinary e-mail prose, correctly punctuated. */
        const val DICTATED_UTTERANCE =
            "Thanks for the update. I will send the revised quotation and the delivery date tomorrow morning."

        /**
         * [dev.brgr.outspoke.ime.TextInjector] commits all but the last six words of an utterance
         * and leaves the rest as composing text, so that tail is what a host app highlights.
         */
        val COMPOSING_TAIL: String =
            DICTATED_UTTERANCE.trimEnd().split(' ').takeLast(6).joinToString(" ")

        /** Alternatives for the word under the cursor in the word-suggestion shot. */
        val SUGGESTIONS_FOR_TAIL_WORD: List<String> =
            listOf("morning.", "mourning.", "warning.", "morning", "marning.")
    }
}

/** The real companion screens in the Scaffold + top-bar shape `SettingsActivity` gives them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompanionScaffold(@StringRes titleRes: Int, content: @Composable () -> Unit) {
    OutspokeTheme {
        // No scroller here: each screen scrolls itself, and wrapping a LazyColumn in a
        // verticalScroll Column is an infinite-height measurement error, not a layout.
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(titleRes)) },
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
            Box(modifier = Modifier.padding(padding)) { content() }
        }
    }
}

/**
 * The IME strip at its production height (20 % of the usable screen) over the host app a store
 * visitor will recognise instantly - composing an e-mail - because that is the product: speech in,
 * punctuated text out. The field and the strip tell the same story: the dictated sentence is in the
 * field, its still-composing tail is highlighted there, and the pill carries the utterance the
 * strip is decoding.
 */
@Composable
private fun HostAppThenKeyboard(
    state: KeyboardUiState,
    amplitude: Float,
    wordSuggestions: List<String> = emptyList(),
) {
    val composing = state is KeyboardUiState.Processing
    val tailStart = UiScreenshotCapture.DICTATED_UTTERANCE.lastIndexOf(UiScreenshotCapture.COMPOSING_TAIL)
    OutspokeTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxWidth().weight(1f).padding(20.dp)) {
                    Text("New message", style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "To   Anna",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(18.dp))
                    OutlinedTextField(
                        value = TextFieldValue(
                            text = UiScreenshotCapture.DICTATED_UTTERANCE,
                            selection = if (composing) {
                                TextRange(tailStart, UiScreenshotCapture.DICTATED_UTTERANCE.length)
                            } else {
                                TextRange(UiScreenshotCapture.DICTATED_UTTERANCE.length)
                            },
                        ),
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp),
                        label = { Text("Message") },
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
