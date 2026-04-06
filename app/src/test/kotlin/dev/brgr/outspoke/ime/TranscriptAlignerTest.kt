package dev.brgr.outspoke.ime

import dev.brgr.outspoke.ime.TranscriptAligner.findNewContent
import dev.brgr.outspoke.ime.TranscriptAligner.normalizeWord
import dev.brgr.outspoke.ime.TranscriptAligner.splitToWords
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * Dedicated unit tests for [TranscriptAligner] - the pure-function alignment logic
 * extracted from [TextInjector].
 *
 * These tests exercise the three alignment layers and their edge cases directly,
 * without needing a [FakeInputConnection] or any Android dependency.
 *
 * Test groups:
 *  1. [splitToWords] - whitespace handling
 *  2. [normalizeWord] - boundary punctuation stripping + case folding
 *  3. [findNewContent] Layer 1 - full prefix match (happy path)
 *  4. [findNewContent] Layer 2 - suffix-prefix overlap (model drift)
 *  5. [findNewContent] Layer 3 - interior scan (post-trim junk tokens)
 *  6. Divergence - complete mismatch returns empty
 *  7. Minimum-overlap-2 guard - single-word coincidences are rejected
 *  8. Regression anchors for specific bugs
 */
class TranscriptAlignerTest {

    @Test
    fun `splitToWords basic sentence`() {
        assertThat("Hello world".splitToWords()).containsExactly("Hello", "world")
    }

    @Test
    fun `splitToWords trims leading and trailing whitespace`() {
        assertThat("  Hello world  ".splitToWords()).containsExactly("Hello", "world")
    }

    @Test
    fun `splitToWords handles multiple internal spaces`() {
        assertThat("Hello   world".splitToWords()).containsExactly("Hello", "world")
    }

    @Test
    fun `splitToWords returns empty for blank string`() {
        assertThat("   ".splitToWords()).isEmpty()
    }

    @Test
    fun `splitToWords returns empty for empty string`() {
        assertThat("".splitToWords()).isEmpty()
    }

    @Test
    fun `splitToWords preserves punctuation attached to words`() {
        assertThat("Hello, world!".splitToWords()).containsExactly("Hello,", "world!")
    }

    @Test
    fun `normalizeWord lowercases`() {
        assertThat("Hello".normalizeWord()).isEqualTo("hello")
    }

    @Test
    fun `normalizeWord strips trailing period`() {
        assertThat("again.".normalizeWord()).isEqualTo("again")
    }

    @Test
    fun `normalizeWord strips trailing comma`() {
        assertThat("denke,".normalizeWord()).isEqualTo("denke")
    }

    @Test
    fun `normalizeWord preserves internal apostrophe`() {
        assertThat("I'm".normalizeWord()).isEqualTo("i'm")
    }

    @Test
    fun `normalizeWord strips leading and trailing punctuation`() {
        assertThat("(word)".normalizeWord()).isEqualTo("word")
    }

    @Test
    fun `normalizeWord on pure punctuation returns empty`() {
        assertThat(",".normalizeWord()).isEmpty()
    }

    @Test
    fun `normalizeWord preserves digits`() {
        assertThat("test123.".normalizeWord()).isEqualTo("test123")
    }

    @Test
    fun `layer 1 - empty committed returns all of fresh`() {
        val result = findNewContent(emptyList(), listOf("Hello", "world"))
        assertThat(result).containsExactly("Hello", "world")
    }

    @Test
    fun `layer 1 - empty committed and empty fresh returns empty`() {
        val result = findNewContent(emptyList(), emptyList())
        assertThat(result).isEmpty()
    }

    @Test
    fun `layer 1 - exact prefix match returns only new words`() {
        val committed = listOf("Now", "I", "lifted")
        val fresh = listOf("Now", "I", "lifted", "the", "record")
        assertThat(findNewContent(committed, fresh)).containsExactly("the", "record")
    }

    @Test
    fun `layer 1 - full prefix match when fresh equals committed returns empty`() {
        val words = listOf("Now", "I", "lifted")
        assertThat(findNewContent(words, words)).isEmpty()
    }

    @Test
    fun `layer 1 - prefix match is case insensitive`() {
        val committed = listOf("now", "i", "lifted")
        val fresh = listOf("Now", "I", "Lifted", "the", "record")
        assertThat(findNewContent(committed, fresh)).containsExactly("the", "record")
    }

    @Test
    fun `layer 1 - prefix match ignores trailing punctuation`() {
        val committed = listOf("Ich", "denke,")
        val fresh = listOf("Ich", "denke,", "dass", "es")
        assertThat(findNewContent(committed, fresh)).containsExactly("dass", "es")
    }

    @Test
    fun `layer 1 - committed longer than fresh with no match returns empty`() {
        // committed has more words than fresh, and the words don't overlap
        val committed = listOf("A", "B", "C", "D", "E")
        val fresh = listOf("X", "Y")
        assertThat(findNewContent(committed, fresh)).isEmpty()
    }

    @Test
    fun `layer 2 - model drops first 3 words, suffix-prefix overlap finds new content`() {
        val committed = listOf("Now", "I", "lifted", "the", "record", "button")
        val fresh = listOf("the", "record", "button", "and", "I'm", "pressing")
        assertThat(findNewContent(committed, fresh))
            .containsExactly("and", "I'm", "pressing")
    }

    @Test
    fun `layer 2 - model drops everything except last 2 words`() {
        val committed = listOf("A", "B", "C", "D", "E")
        val fresh = listOf("D", "E", "F", "G")
        assertThat(findNewContent(committed, fresh)).containsExactly("F", "G")
    }

    @Test
    fun `layer 2 - overlap with case and punctuation differences`() {
        val committed = listOf("Es", "ist", "gut.")
        val fresh = listOf("ist", "gut.", "Und", "jetzt")
        assertThat(findNewContent(committed, fresh)).containsExactly("Und", "jetzt")
    }

    @Test
    fun `layer 2 - entire committed is suffix of fresh start`() {
        // committed = ["C","D"], fresh = ["C","D","E","F"]
        // Layer 1 covers this actually, but worth verifying.
        val committed = listOf("C", "D")
        val fresh = listOf("C", "D", "E", "F")
        assertThat(findNewContent(committed, fresh)).containsExactly("E", "F")
    }

    @Test
    fun `layer 2 - overlap with nothing new after it`() {
        val committed = listOf("A", "B", "C", "D")
        val fresh = listOf("C", "D")
        assertThat(findNewContent(committed, fresh)).isEmpty()
    }

    @Test
    fun `layer 3 - one leading junk token before the overlap`() {
        // committed ends with ["record","button"], fresh has junk "Angabe" at position 0
        val committed = listOf("Ich", "drücke", "den", "record", "button")
        val fresh = listOf("Angabe", "record", "button", "und", "mehr")
        assertThat(findNewContent(committed, fresh)).containsExactly("und", "mehr")
    }

    @Test
    fun `layer 3 - three leading junk tokens before the overlap`() {
        val committed = listOf("Alpha", "Beta", "Gamma", "Delta", "Epsilon")
        val fresh = listOf("junk1", "junk2", "junk3", "Delta", "Epsilon", "Zeta")
        assertThat(findNewContent(committed, fresh)).containsExactly("Zeta")
    }

    @Test
    fun `layer 3 - interior overlap with nothing new after it`() {
        val committed = listOf("A", "B", "C", "D")
        val fresh = listOf("junk", "C", "D")
        assertThat(findNewContent(committed, fresh)).isEmpty()
    }

    @Test
    fun `layer 3 - interior scan limited to last 6 committed words`() {
        // committed has 10 words, interior scan only checks the last 6.
        // If the overlap is on words beyond that window, it should NOT match.
        val committed = listOf("w1", "w2", "w3", "w4", "w5", "w6", "w7", "w8", "w9", "w10")
        // Overlap on w3,w4 (positions 2-3) which are outside the last-6 window [w5..w10]
        val fresh = listOf("junk", "w3", "w4", "new1")
        // No match expected because w3,w4 are not in the tail-6
        assertThat(findNewContent(committed, fresh)).isEmpty()
    }

    @Test
    fun `layer 3 - interior scan finds overlap within last 6 committed words`() {
        val committed = listOf("w1", "w2", "w3", "w4", "w5", "w6", "w7", "w8", "w9", "w10")
        // Overlap on w9,w10 which ARE in the tail-6 window [w5..w10]
        val fresh = listOf("junk", "w9", "w10", "new1")
        assertThat(findNewContent(committed, fresh)).containsExactly("new1")
    }

    @Test
    fun `complete divergence returns empty list`() {
        val committed = listOf("Heute", "ist", "Montag")
        val fresh = listOf("Apfel", "Birne", "Kirsche")
        assertThat(findNewContent(committed, fresh)).isEmpty()
    }

    @Test
    fun `single committed word with unrelated fresh returns empty`() {
        val committed = listOf("Hallo")
        val fresh = listOf("Welt", "ist", "schön")
        assertThat(findNewContent(committed, fresh)).isEmpty()
    }

    @Test
    fun `single-word overlap on common word is rejected by layer 2`() {
        // "nicht" appears at the end of committed AND at the start of fresh,
        // but as a single-word overlap it must NOT create a false alignment.
        val committed = listOf("Ich", "weiß", "es", "nicht.")
        val fresh = listOf("nicht", "über", "das", "Thema", "reden.")
        assertThat(findNewContent(committed, fresh)).isEmpty()
    }

    @Test
    fun `single-word overlap on common word is rejected by layer 3 interior scan`() {
        // "nicht" exists as a single interior match - must not produce a false positive.
        val committed = listOf("Er", "hat", "nicht", "viel", "gesagt.")
        val fresh = listOf("junk", "nicht", "mehr", "da.")
        // "nicht" alone at interior position 1 must not be treated as an overlap
        assertThat(findNewContent(committed, fresh)).isEmpty()
    }

    @Test
    fun `two-word overlap is accepted by layer 2`() {
        val committed = listOf("Ich", "weiß", "es", "nicht", "mehr.")
        val fresh = listOf("nicht", "mehr.", "Und", "jetzt")
        assertThat(findNewContent(committed, fresh)).containsExactly("Und", "jetzt")
    }

    @Test
    fun `two-word overlap is accepted by layer 3 interior scan`() {
        val committed = listOf("Er", "hat", "nicht", "viel", "gesagt.")
        val fresh = listOf("junk", "viel", "gesagt.", "Danach")
        assertThat(findNewContent(committed, fresh)).containsExactly("Danach")
    }

    /**
     * Bug 5A regression: a single-word coincidence on "nicht" after multiple trims
     * must not cascade into corrupted alignment.
     */
    @Test
    fun `Bug 5A - single word nicht does not produce false alignment`() {
        val committed = listOf("gerade", "entwickeln.", "Es", "gibt", "einige", "Punkte.")
        val fresh = listOf("nicht", "über", "das", "Projekt")
        assertThat(findNewContent(committed, fresh)).isEmpty()
    }

    /**
     * Bug A regression: a pure-punctuation token that normalizes to "" must not
     * corrupt alignment for all subsequent strides.
     */
    @Test
    fun `pure punctuation token normalizes to empty and does not break alignment`() {
        // If committed contains a comma-only token, it normalizes to "".
        // Any fresh word also normalizes to "" at its boundary - this must not
        // create a false match.  (In practice cleanTranscript strips this before
        // it reaches the aligner, but the aligner must be resilient.)
        val committed = listOf(",", "Jetzt")
        val fresh = listOf("Ich", "denke,", "dass")
        // "," normalizes to "" and "Ich" normalizes to "ich" - no match.
        // No overlap of ≥2 words → empty.
        assertThat(findNewContent(committed, fresh)).isEmpty()
    }

    /**
     * The doc example from the KDoc: committed ends with button, fresh drops
     * the first three words.
     */
    @Test
    fun `KDoc example 1 - suffix-prefix overlap`() {
        val committed = listOf("Now", "I", "lifted", "the", "record", "button")
        val fresh = listOf("the", "record", "button", "and", "I'm", "pressing")
        assertThat(findNewContent(committed, fresh))
            .containsExactly("and", "I'm", "pressing")
    }

    /**
     * The doc example from the KDoc: simple prefix match.
     */
    @Test
    fun `KDoc example 2 - simple prefix`() {
        val committed = listOf("Now", "I", "lifted")
        val fresh = listOf("Now", "I", "lifted", "the", "record")
        assertThat(findNewContent(committed, fresh)).containsExactly("the", "record")
    }

    @Test
    fun `fresh is empty returns empty`() {
        val committed = listOf("A", "B")
        assertThat(findNewContent(committed, emptyList())).isEmpty()
    }

    @Test
    fun `single committed word matches single fresh word returns empty (prefix match)`() {
        assertThat(findNewContent(listOf("Hello"), listOf("Hello"))).isEmpty()
    }

    @Test
    fun `single committed word with fresh extending it returns new words`() {
        assertThat(findNewContent(listOf("Hello"), listOf("Hello", "world")))
            .containsExactly("world")
    }

    @Test
    fun `large committed and fresh with correct prefix match`() {
        val committed = (1..50).map { "word$it" }
        val fresh = (1..50).map { "word$it" } + listOf("new1", "new2")
        assertThat(findNewContent(committed, fresh)).containsExactly("new1", "new2")
    }

    @Test
    fun `layer 2 picks the longest overlap when multiple are possible`() {
        // committed ends with [B, C, D], fresh starts with [C, D, ...] and also has [D, ...].
        // Layer 2 should find the longest suffix-prefix overlap.
        val committed = listOf("A", "B", "C", "D")
        val fresh = listOf("B", "C", "D", "E")
        // Longest overlap is 3: [B,C,D] - returns [E]
        assertThat(findNewContent(committed, fresh)).containsExactly("E")
    }
}

