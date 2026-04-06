package dev.brgr.outspoke.inference

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * Layer-1 unit tests for [collapseStutters].
 *
 * A "stutter" is a single word repeated 3 or more times consecutively.
 * Two consecutive repetitions are below the threshold and must be left untouched.
 */
class CollapseStuttersTest {

    @Test
    fun `three consecutive identical words collapse to one`() {
        assertThat("no no no way".collapseStutters()).isEqualTo("no way")
    }

    @Test
    fun `two consecutive identical words are kept - threshold is 3`() {
        assertThat("no no way".collapseStutters()).isEqualTo("no no way")
    }
}

