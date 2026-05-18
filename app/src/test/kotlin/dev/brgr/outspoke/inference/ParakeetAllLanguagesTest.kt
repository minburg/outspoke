package dev.brgr.outspoke.inference

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * Regression tests proving that the post-processing pipeline (script-hallucination filter,
 * transcript cleaning) does not block or corrupt transcription output for every language
 * supported by Parakeet v3.
 *
 * One test per language:
 *  - [isScriptHallucination] must return `false` for a representative native-script sentence.
 *  - [cleanTranscript] must return a non-empty string (pipeline does not swallow the text).
 *
 * No ONNX model is loaded — these are pure-Kotlin unit tests.
 */
class ParakeetAllLanguagesTest {

    // ── Latin-script languages ───────────────────────────────────────────────

    @Test
    fun `Bulgarian (bg) - Cyrillic script is not flagged as hallucination`() {
        val text = "Здравейте, как сте днес?"
        assertThat(isScriptHallucination(text, "bg")).isFalse()
        assertThat(cleanTranscript(text, language = "bg")).isNotBlank()
    }

    @Test
    fun `Croatian (hr) - Latin script is not flagged as hallucination`() {
        val text = "Dobar dan, kako ste danas?"
        assertThat(isScriptHallucination(text, "hr")).isFalse()
        assertThat(cleanTranscript(text, language = "hr")).isNotBlank()
    }

    @Test
    fun `Czech (cs) - Latin script with diacritics is not flagged as hallucination`() {
        val text = "Dobrý den, jak se máte dnes?"
        assertThat(isScriptHallucination(text, "cs")).isFalse()
        assertThat(cleanTranscript(text, language = "cs")).isNotBlank()
    }

    @Test
    fun `Danish (da) - Latin script is not flagged as hallucination`() {
        val text = "Goddag, hvordan har du det i dag?"
        assertThat(isScriptHallucination(text, "da")).isFalse()
        assertThat(cleanTranscript(text, language = "da")).isNotBlank()
    }

    @Test
    fun `Dutch (nl) - Latin script is not flagged as hallucination`() {
        val text = "Goedendag, hoe gaat het vandaag met u?"
        assertThat(isScriptHallucination(text, "nl")).isFalse()
        assertThat(cleanTranscript(text, language = "nl")).isNotBlank()
    }

    @Test
    fun `English (en) - Latin script is not flagged as hallucination`() {
        val text = "Hello, how are you doing today?"
        assertThat(isScriptHallucination(text, "en")).isFalse()
        assertThat(cleanTranscript(text, language = "en")).isNotBlank()
    }

    @Test
    fun `Estonian (et) - Latin script with diacritics is not flagged as hallucination`() {
        val text = "Tere päevast, kuidas teil täna läheb?"
        assertThat(isScriptHallucination(text, "et")).isFalse()
        assertThat(cleanTranscript(text, language = "et")).isNotBlank()
    }

    @Test
    fun `Finnish (fi) - Latin script with diacritics is not flagged as hallucination`() {
        val text = "Hyvää päivää, kuinka teillä menee tänään?"
        assertThat(isScriptHallucination(text, "fi")).isFalse()
        assertThat(cleanTranscript(text, language = "fi")).isNotBlank()
    }

    @Test
    fun `French (fr) - Latin script with accents is not flagged as hallucination`() {
        val text = "Bonjour, comment allez-vous aujourd'hui?"
        assertThat(isScriptHallucination(text, "fr")).isFalse()
        assertThat(cleanTranscript(text, language = "fr")).isNotBlank()
    }

    @Test
    fun `German (de) - Latin script with umlauts is not flagged as hallucination`() {
        val text = "Guten Tag, wie geht es Ihnen heute?"
        assertThat(isScriptHallucination(text, "de")).isFalse()
        assertThat(cleanTranscript(text, language = "de")).isNotBlank()
    }

    @Test
    fun `Greek (el) - Greek script is not flagged as hallucination`() {
        val text = "Καλημέρα, πώς είστε σήμερα;"
        assertThat(isScriptHallucination(text, "el")).isFalse()
        assertThat(cleanTranscript(text, language = "el")).isNotBlank()
    }

    @Test
    fun `Hungarian (hu) - Latin script with diacritics is not flagged as hallucination`() {
        val text = "Jó napot kívánok, hogy van ma?"
        assertThat(isScriptHallucination(text, "hu")).isFalse()
        assertThat(cleanTranscript(text, language = "hu")).isNotBlank()
    }

    @Test
    fun `Italian (it) - Latin script is not flagged as hallucination`() {
        val text = "Buongiorno, come sta oggi?"
        assertThat(isScriptHallucination(text, "it")).isFalse()
        assertThat(cleanTranscript(text, language = "it")).isNotBlank()
    }

    @Test
    fun `Latvian (lv) - Latin script with diacritics is not flagged as hallucination`() {
        val text = "Labdien, kā jums šodien klājas?"
        assertThat(isScriptHallucination(text, "lv")).isFalse()
        assertThat(cleanTranscript(text, language = "lv")).isNotBlank()
    }

    @Test
    fun `Lithuanian (lt) - Latin script with diacritics is not flagged as hallucination`() {
        val text = "Laba diena, kaip šiandien sekasi?"
        assertThat(isScriptHallucination(text, "lt")).isFalse()
        assertThat(cleanTranscript(text, language = "lt")).isNotBlank()
    }

    @Test
    fun `Maltese (mt) - Latin script with special characters is not flagged as hallucination`() {
        val text = "Bonġornu, kif intom illum?"
        assertThat(isScriptHallucination(text, "mt")).isFalse()
        assertThat(cleanTranscript(text, language = "mt")).isNotBlank()
    }

    @Test
    fun `Polish (pl) - Latin script with diacritics is not flagged as hallucination`() {
        val text = "Dzień dobry, jak się pan dzisiaj miewa?"
        assertThat(isScriptHallucination(text, "pl")).isFalse()
        assertThat(cleanTranscript(text, language = "pl")).isNotBlank()
    }

    @Test
    fun `Portuguese (pt) - Latin script with accents is not flagged as hallucination`() {
        val text = "Bom dia, como está o senhor hoje?"
        assertThat(isScriptHallucination(text, "pt")).isFalse()
        assertThat(cleanTranscript(text, language = "pt")).isNotBlank()
    }

    @Test
    fun `Romanian (ro) - Latin script with diacritics is not flagged as hallucination`() {
        val text = "Bună ziua, cum vă simțiți astăzi?"
        assertThat(isScriptHallucination(text, "ro")).isFalse()
        assertThat(cleanTranscript(text, language = "ro")).isNotBlank()
    }

    @Test
    fun `Russian (ru) - Cyrillic script is not flagged as hallucination`() {
        val text = "Добрый день, как вы себя чувствуете сегодня?"
        assertThat(isScriptHallucination(text, "ru")).isFalse()
        assertThat(cleanTranscript(text, language = "ru")).isNotBlank()
    }

    @Test
    fun `Slovak (sk) - Latin script with diacritics is not flagged as hallucination`() {
        val text = "Dobrý deň, ako sa dnes máte?"
        assertThat(isScriptHallucination(text, "sk")).isFalse()
        assertThat(cleanTranscript(text, language = "sk")).isNotBlank()
    }

    @Test
    fun `Slovenian (sl) - Latin script with diacritics is not flagged as hallucination`() {
        val text = "Dober dan, kako ste danes?"
        assertThat(isScriptHallucination(text, "sl")).isFalse()
        assertThat(cleanTranscript(text, language = "sl")).isNotBlank()
    }

    @Test
    fun `Spanish (es) - Latin script with accents is not flagged as hallucination`() {
        val text = "Buenos días, ¿cómo está usted hoy?"
        assertThat(isScriptHallucination(text, "es")).isFalse()
        assertThat(cleanTranscript(text, language = "es")).isNotBlank()
    }

    @Test
    fun `Swedish (sv) - Latin script with diacritics is not flagged as hallucination`() {
        val text = "God dag, hur mår ni idag?"
        assertThat(isScriptHallucination(text, "sv")).isFalse()
        assertThat(cleanTranscript(text, language = "sv")).isNotBlank()
    }

    @Test
    fun `Ukrainian (uk) - Cyrillic script is not flagged as hallucination`() {
        val text = "Добрий день, як ви почуваєтесь сьогодні?"
        assertThat(isScriptHallucination(text, "uk")).isFalse()
        assertThat(cleanTranscript(text, language = "uk")).isNotBlank()
    }

    // ── Script cross-check: known hallucination patterns ARE flagged ─────────

    @Test
    fun `CJK characters are flagged as hallucination regardless of language`() {
        // Chinese/Japanese/Korean characters are never a valid Parakeet output.
        val text = "今日は良い天気ですね"
        assertThat(isScriptHallucination(text, "en")).isTrue()
        assertThat(isScriptHallucination(text, "ru")).isTrue()
    }

    @Test
    fun `Emoji and symbol noise is flagged as hallucination`() {
        val text = "♪♪♪ ★ ♫"
        assertThat(isScriptHallucination(text, "en")).isTrue()
    }

    @Test
    fun `Cyrillic text with language en is NOT flagged as hallucination`() {
        // A Russian user with UI set to "en" (auto) must still get their transcript.
        // The check is script-consistency based, not language-setting based.
        val text = "Добрый день как дела"
        assertThat(isScriptHallucination(text, "en")).isFalse()
    }

    @Test
    fun `Latin text with language ru is NOT flagged as hallucination`() {
        // Foreign names/acronyms are expected to appear in Cyrillic-language transcripts.
        val text = "NATO"
        assertThat(isScriptHallucination(text, "ru")).isFalse()
    }
}

// ---------------------------------------------------------------------------
// Top-level wrapper so the internal extension function can be called from a
// test without a receiver object.
// ---------------------------------------------------------------------------
private fun cleanTranscript(text: String, language: String): String =
    text.cleanTranscript(language = language)
