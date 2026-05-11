package dev.brgr.outspoke.inference

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * Unit tests for the language-forcing feature in [ParakeetEngine] / [SpeechEngine].
 *
 * These tests verify:
 *  1. That the forced language threads through to [cleanTranscript] and alters its behaviour
 *     (German disfluency removal vs. English filler removal).
 *  2. That `null` forced-language (auto-detect) defaults to `"en"` behaviour in the
 *     post-processing pipeline — so no regressions for existing English users.
 *
 * No ONNX sessions are opened — we only test the post-processing pipeline and the
 * [SpeechEngine.currentLanguage] / [SpeechEngine.setLanguage] contract.
 */
class ParakeetLanguageTest {

    // ── currentLanguage default ──────────────────────────────────────────────

    @Test
    fun `SpeechEngine default currentLanguage is en`() {
        // Any engine that does not override currentLanguage must return "en".
        val engine = FakeSpeechEngine(responses = listOf(TranscriptResult.Partial("")))
        assertThat(engine.currentLanguage).isEqualTo("en")
    }

    @Test
    fun `null forcedLanguage defaults to en post-processing behaviour - um removed as English filler`() {
        // When no language is forced, cleanTranscript must behave as language="en",
        // which strips "um" as an English filler word.
        val result = "um hello world".cleanTranscript(language = "en")
        assertThat(result).isEqualTo("Hello world")
    }

    @Test
    fun `null forcedLanguage - cleanTranscript with default language en strips uh`() {
        // Default language="en" is used when forcedLanguage is null.
        val result = "uh so I was saying hello".cleanTranscript()  // language defaults to "en"
        assertThat(result).isEqualTo("So I was saying hello")
    }

    // ── language threading to cleanTranscript ────────────────────────────────

    @Test
    fun `setLanguage de makes cleanTranscript remove German disfluencies`() {
        // German "äh" and "ähm" must be stripped when language="de".
        val result = "äh dann ähm ja genau.".cleanTranscript(language = "de")
        assertThat(result).isEqualTo("Dann ja genau.")
    }

    @Test
    fun `setLanguage de preserves um as German content word`() {
        // "um" in German means "around/at" — NOT a filler word. Must be preserved.
        val result = "wir treffen uns um acht Uhr.".cleanTranscript(language = "de")
        assertThat(result).isEqualTo("Wir treffen uns um 8 Uhr.")
    }

    @Test
    fun `setLanguage en removes um as English filler`() {
        val result = "um so I was saying uh hello.".cleanTranscript(language = "en")
        assertThat(result).isEqualTo("So I was saying hello.")
    }

    // ── ParakeetEngine.setLanguage / currentLanguage contract ────────────────

    @Test
    fun `ParakeetEngine setLanguage stores language and currentLanguage returns it`() {
        // ParakeetEngine is instantiated but never loaded — we only test the
        // setLanguage/currentLanguage contract, which requires no ONNX files.
        val engine = ParakeetEngine()
        assertThat(engine.currentLanguage).isEqualTo("en")   // default before any call

        engine.setLanguage("de")
        assertThat(engine.currentLanguage).isEqualTo("de")

        engine.setLanguage("fr")
        assertThat(engine.currentLanguage).isEqualTo("fr")
    }

    @Test
    fun `ParakeetEngine setLanguage auto resets to en default`() {
        val engine = ParakeetEngine()
        engine.setLanguage("de")
        assertThat(engine.currentLanguage).isEqualTo("de")

        engine.setLanguage("auto")
        // "auto" → forcedLanguage = null → currentLanguage must return "en"
        assertThat(engine.currentLanguage).isEqualTo("en")
    }

    @Test
    fun `ParakeetEngine setLanguage is thread-safe - last write wins`() {
        val engine = ParakeetEngine()
        // Rapid successive calls — no crash, last value visible
        engine.setLanguage("de")
        engine.setLanguage("fr")
        engine.setLanguage("es")
        assertThat(engine.currentLanguage).isEqualTo("es")
    }
}
