package dev.brgr.outspoke.inference

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * Layer-1 unit tests for [collapseRepeatedPhrases].
 *
 * Verifies that consecutive adjacent repeated words and multi-word phrases are
 * collapsed to a single occurrence while non-adjacent repetitions are preserved.
 */
class CollapsePhrasesTest {

    @Test
    fun `repeated single word is collapsed to one occurrence`() {
        assertThat("gut gut aus".collapseRepeatedPhrases()).isEqualTo("gut aus")
    }

    @Test
    fun `repeated two-word phrase is collapsed to one occurrence`() {
        assertThat("zwei Sachen zwei Sachen die".collapseRepeatedPhrases())
            .isEqualTo("zwei Sachen die")
    }

    @Test
    fun `triple repeat of two-word phrase collapses to one occurrence`() {
        assertThat("ganz gut ganz gut ganz gut aus".collapseRepeatedPhrases())
            .isEqualTo("ganz gut aus")
    }

    @Test
    fun `non-adjacent repeat is kept intact`() {
        // "gut" appears twice but not consecutively - must not be collapsed.
        assertThat("gut und gut".collapseRepeatedPhrases()).isEqualTo("gut und gut")
    }
}

