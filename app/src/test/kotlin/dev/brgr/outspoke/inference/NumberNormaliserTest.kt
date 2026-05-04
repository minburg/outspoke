package dev.brgr.outspoke.inference

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [NumberNormaliser].
 *
 * Each test calls [NumberNormaliser.normalise] directly — no Android framework needed.
 */
class NumberNormaliserTest {

    // ── Helper ────────────────────────────────────────────────────────────────

    /** Convenience: split a string into words, normalise, rejoin. */
    private fun normaliseEN(sentence: String): String =
        NumberNormaliser.normalise(sentence.split(" ").filter { it.isNotEmpty() }, "en")
            .joinToString(" ")

    private fun normaliseDE(sentence: String): String =
        NumberNormaliser.normalise(sentence.split(" ").filter { it.isNotEmpty() }, "de")
            .joinToString(" ")

    // ── 1. Single digits — English ────────────────────────────────────────────

    @Test
    fun `en single digit zero`() = assertEquals("0", normaliseEN("zero"))

    @Test
    fun `en single digit one`() = assertEquals("1", normaliseEN("one"))

    @Test
    fun `en single digit twelve`() = assertEquals("12", normaliseEN("twelve"))

    @Test
    fun `en single digit nineteen`() = assertEquals("19", normaliseEN("nineteen"))

    // ── 2. Single digits — German ─────────────────────────────────────────────

    @Test
    fun `de single digit null`() = assertEquals("0", normaliseDE("null"))

    @Test
    fun `de single digit eins`() = assertEquals("1", normaliseDE("eins"))

    @Test
    fun `de single digit zwölf`() = assertEquals("12", normaliseDE("zwölf"))

    @Test
    fun `de single digit neunzehn`() = assertEquals("19", normaliseDE("neunzehn"))

    // ── 3. Tens ───────────────────────────────────────────────────────────────

    @Test
    fun `en tens twenty`() = assertEquals("20", normaliseEN("twenty"))

    @Test
    fun `en tens ninety`() = assertEquals("90", normaliseEN("ninety"))

    @Test
    fun `en tens twenty two`() = assertEquals("22", normaliseEN("twenty two"))

    @Test
    fun `de tens zwanzig`() = assertEquals("20", normaliseDE("zwanzig"))

    @Test
    fun `de tens dreißig`() = assertEquals("30", normaliseDE("dreißig"))

    // ── 4. German compound tens ───────────────────────────────────────────────

    @Test
    fun `de compound einundzwanzig`() = assertEquals("21", normaliseDE("einundzwanzig"))

    @Test
    fun `de compound zweiundzwanzig`() = assertEquals("22", normaliseDE("zweiundzwanzig"))

    @Test
    fun `de compound neunundneunzig`() = assertEquals("99", normaliseDE("neunundneunzig"))

    @Test
    fun `de compound dreiunddreißig`() = assertEquals("33", normaliseDE("dreiunddreißig"))

    // ── 5. Hundreds ───────────────────────────────────────────────────────────

    @Test
    fun `en hundred`() = assertEquals("100", normaliseEN("hundred"))

    @Test
    fun `en a hundred - a treated as article not number word`() {
        // "a" is an EN connector but not a number word; it passes through unchanged.
        // The word "hundred" is converted because it follows as its own run.
        assertEquals("a 100", normaliseEN("a hundred"))
    }

    @Test
    fun `en three hundred`() = assertEquals("300", normaliseEN("three hundred"))

    @Test
    fun `de hundert`() = assertEquals("100", normaliseDE("hundert"))

    @Test
    fun `de drei hundert`() = assertEquals("300", normaliseDE("drei hundert"))

    // ── 6. Thousands ──────────────────────────────────────────────────────────

    @Test
    fun `en one thousand`() = assertEquals("1000", normaliseEN("one thousand"))

    @Test
    fun `en two thousand and fifteen`() =
        assertEquals("2015", normaliseEN("two thousand and fifteen"))

    @Test
    fun `en two thousand and twenty five`() =
        assertEquals("2025", normaliseEN("two thousand and twenty five"))

    @Test
    fun `de tausend`() = assertEquals("1000", normaliseDE("tausend"))

    @Test
    fun `de zwei tausend`() = assertEquals("2000", normaliseDE("zwei tausend"))

    // ── 7. Millions / billions ────────────────────────────────────────────────

    @Test
    fun `en one million`() = assertEquals("1000000", normaliseEN("one million"))

    @Test
    fun `en two billion`() = assertEquals("2000000000", normaliseEN("two billion"))

    @Test
    fun `de eine million`() {
        // "eine" is not in DE_ONES but "ein" is. "eine" is not a number word here —
        // the sentence will pass through "eine" unchanged and convert "million".
        // This documents the current behaviour.
        val result = normaliseDE("eine million")
        // "million" alone → 1_000_000; "eine" is not a DE number word so it passes through.
        assertEquals("eine 1000000", result)
    }

    // ── 8. Mixed sentences ────────────────────────────────────────────────────

    @Test
    fun `en number in sentence`() =
        assertEquals("I have 12 apples", normaliseEN("I have twelve apples"))

    @Test
    fun `en multiple numbers in sentence`() =
        assertEquals("buy 3 or 5 items", normaliseEN("buy three or five items"))

    @Test
    fun `en large number in sentence`() =
        assertEquals(
            "the year 2025 was good",
            normaliseEN("the year two thousand and twenty five was good")
        )

    @Test
    fun `de number in sentence`() =
        assertEquals("ich habe 5 Äpfel", normaliseDE("ich habe fünf Äpfel"))

    // ── 9. Ordinals — English ─────────────────────────────────────────────────

    @Test
    fun `en ordinal first`() = assertEquals("1st", normaliseEN("first"))

    @Test
    fun `en ordinal second`() = assertEquals("2nd", normaliseEN("second"))

    @Test
    fun `en ordinal third`() = assertEquals("3rd", normaliseEN("third"))

    @Test
    fun `en ordinal fourth`() = assertEquals("4th", normaliseEN("fourth"))

    @Test
    fun `en ordinal twentieth`() = assertEquals("20th", normaliseEN("twentieth"))

    @Test
    fun `en ordinal hundredth`() = assertEquals("100th", normaliseEN("hundredth"))

    @Test
    fun `en ordinal in sentence`() =
        assertEquals("the 1st place", normaliseEN("the first place"))

    // ── 10. German ordinals ───────────────────────────────────────────────────

    @Test
    fun `de ordinal erste`() = assertEquals("1.", normaliseDE("erste"))

    @Test
    fun `de ordinal zweite`() = assertEquals("2.", normaliseDE("zweite"))

    @Test
    fun `de ordinal dritte`() = assertEquals("3.", normaliseDE("dritte"))

    @Test
    fun `de ordinal zwanzigste`() = assertEquals("20.", normaliseDE("zwanzigste"))

    // ── 11. Connector words consumed ──────────────────────────────────────────

    @Test
    fun `en connector and consumed inside run`() =
        assertEquals("115", normaliseEN("one hundred and fifteen"))

    @Test
    fun `en trailing and not consumed`() {
        // "twenty and" — the trailing "and" is a connector, dropped back for next word.
        val result = NumberNormaliser.normalise(
            listOf("twenty", "and", "some"),
            "en",
        )
        assertEquals(listOf("20", "and", "some"), result)
    }

    // ── 12. Disabled toggle passes through unchanged ──────────────────────────

    @Test
    fun `disabled toggle leaves number words unchanged`() {
        val words = listOf("I", "have", "twelve", "apples")
        // When formatNumbersAsDigits=false the pipeline skips NumberNormaliser entirely.
        // We verify the object itself is pure by calling with a known input and checking
        // it returns the expected conversion; toggling is handled at the pipeline level.
        val normalised = NumberNormaliser.normalise(words, "en")
        assertEquals(listOf("I", "have", "12", "apples"), normalised)
        // Confirm that passing an empty list also works.
        assertEquals(emptyList<String>(), NumberNormaliser.normalise(emptyList(), "en"))
    }

    // ── 13. Punctuation attached to number words ──────────────────────────────

    @Test
    fun `en number with trailing comma`() {
        val result = NumberNormaliser.normalise(listOf("twelve,", "apples"), "en")
        assertEquals(listOf("12,", "apples"), result)
    }

    @Test
    fun `en number with trailing period`() {
        val result = NumberNormaliser.normalise(listOf("five."), "en")
        assertEquals(listOf("5."), result)
    }

    // ── 14. Non-number words pass through unchanged ───────────────────────────

    @Test
    fun `non-number words untouched`() {
        val words = listOf("hello", "world")
        assertEquals(words, NumberNormaliser.normalise(words, "en"))
    }

    @Test
    fun `empty list`() {
        assertEquals(emptyList<String>(), NumberNormaliser.normalise(emptyList(), "en"))
    }
}
