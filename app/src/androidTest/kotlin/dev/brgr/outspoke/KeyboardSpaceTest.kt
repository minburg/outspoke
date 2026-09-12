package dev.brgr.outspoke

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.ui.test.assertIsDisplayed
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.brgr.outspoke.ui.keyboard.KeyboardScreen
import dev.brgr.outspoke.ui.keyboard.KeyboardUiState
import dev.brgr.outspoke.ui.theme.OutspokeKeyboardTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyboardSpaceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun spaceButtonIsVisibleAndAccessibleOnCompactKeyboard() {
        compose.activityRule.scenario.onActivity {
            it.setShowWhenLocked(true)
            it.setTurnScreenOn(true)
        }
        var spaces = 0
        compose.setContent {
            OutspokeKeyboardTheme {
                Box(Modifier.size(width = 320.dp, height = 180.dp).testTag("keyboard")) {
                    KeyboardScreen(
                        uiState = KeyboardUiState.Idle,
                        amplitude = 0f,
                        isContinuous = false,
                        triggerMode = "HOLD",
                        isWhisperEngine = false,
                        whisperLanguage = "auto",
                        onWhisperLanguageSelected = {},
                        onRecordStart = {}, onRecordStop = {}, onContinuousModeEnabled = {},
                        onDeleteChar = {}, onDeleteWord = {}, onDeleteAll = {},
                        onEnterAction = {}, onSwitchKeyboard = {}, onOpenCompanionApp = {},
                        onInsertSpace = { spaces++ },
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("Insert space").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, spaces) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "keyboard-space.png").outputStream().use {
            compose.onNodeWithTag("keyboard").captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
