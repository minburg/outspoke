package dev.brgr.outspoke.inference

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * Unit tests for the pure [detokenizeNeMoTokens] against a synthetic NeMo-style
 * vocabulary (no model required): word-boundary markers, index-padding slots, and
 * the digit/currency word-boundary repair that keeps dictated numbers off the
 * preceding word ("is5551212" → "is 5551212").
 */
class DetokenizeNeMoTokensTest {

    /** Synthesized vocab mirroring the NeMo export layout; blank sits above all ids used here. */
    private val vocab = arrayOf(
        /*  0 */ "▁My",
        /*  1 */ "▁number",
        /*  2 */ "▁is",
        /*  3 */ "▁born",
        /*  4 */ "▁in",
        /*  5 */ "▁Order",
        /*  6 */ "▁Pizzas",
        /*  7 */ "▁Dial",
        /*  8 */ "▁was",
        /*  9 */ "▁buying",
        /* 10 */ "▁items",
        /* 11 */ "0",
        /* 12 */ "1",
        /* 13 */ "2",
        /* 14 */ "3",
        /* 15 */ "4",
        /* 16 */ "5",
        /* 17 */ "6",
        /* 18 */ "7",
        /* 19 */ "8",
        /* 20 */ "9",
        /* 21 */ "$",
        /* 22 */ ",",
        /* 23 */ ".",
        /* 24 */ "▁1980ess",   // index-padding slot exposing "ess"
        /* 25 */ "1980ess",    // same slot without marker
        /* 26 */ "rd",         // ordinal byte continuation
        /* 27 */ "▁I",
        /* 28 */ "▁a",
    )
    private val blank = 99

    private fun d(vararg ids: Int) = detokenizeNeMoTokens(ids.toList(), vocab, blank)

    @Test
    fun `digit run after a letter starts a new word`() {
        assertThat(d(0, 1, 2, 16, 16, 16, 12, 13, 12, 13, 23))
            .isEqualTo("My number is 5551212.")
    }

    @Test
    fun `year digits split from the preceding word`() {
        assertThat(d(0, 8, 3, 4, 12, 20, 19, 15, 23))
            .isEqualTo("My was born in 1984.")
    }

    @Test
    fun `currency symbol after a letter starts a new word and keeps its digits`() {
        assertThat(d(2, 21, 12, 22, 13, 16, 11, 23))
            .isEqualTo("is $1,250.")
    }

    @Test
    fun `digits run together across tokens without spaces`() {
        assertThat(d(7, 20, 12, 12))
            .isEqualTo("Dial 911")
    }

    @Test
    fun `digit continuation after marker token is unchanged`() {
        assertThat(d(5, 13, 6))
            .isEqualTo("Order 2 Pizzas")
    }

    @Test
    fun `ordinal byte suffix stays glued to its digits`() {
        assertThat(d(27, 13, 26))
            .isEqualTo("I 2rd")
    }

    @Test
    fun `index-padding slots still resolve to their letter suffix`() {
        // Marker + digit-prefixed slot → new word; bare slot → continues previous word.
        assertThat(d(24)).isEqualTo("ess")
        assertThat(d(28, 25)).isEqualTo("aess")
    }

    @Test
    fun `pure padded index slot is preserved as digits`() {
        // Regression guard for the 29619d7 fix: pure-digit tokens are real emissions.
        assertThat(d(7, 18, 19, 17)).isEqualTo("Dial 786")
    }

    @Test
    fun `blank id out of range and negative ids are skipped`() {
        assertThat(d(-1, blank, blank + 1, 27, 2)).isEqualTo("I is")
    }
}
