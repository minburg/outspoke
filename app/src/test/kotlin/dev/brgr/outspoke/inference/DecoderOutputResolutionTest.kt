package dev.brgr.outspoke.inference

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test

/**
 * Regression tests for decoder_joint output-name resolution (issue #39).
 *
 * The engine must never read decoder results by output POSITION: exports differ in
 * output order (orukeet lists `prednet_lengths` first) and append extra outputs
 * (Olicorne ultra adds rank-4 `topk_logits`/`duration_logits`). The (name, shape)
 * lists below are taken from `onnxruntime` output dumps of the real exports:
 * istupakov v3, Olicorne ultra, and ysdede orukeet.
 */
class DecoderOutputResolutionTest {

    private companion object {
        const val BLANK = 8192  // '<blk>' in the shared 8193-entry vocab

        val LOGITS = longArrayOf(1, 1, 1, 8198)
        val STATES = longArrayOf(2, 1, 640)
        val PREP_LEN = longArrayOf(-1)
        val TOPK = longArrayOf(1, 1, 1, 16)
        val DUR_HEAD = longArrayOf(1, 1, 1, 5)
        val LSE = longArrayOf(1, 1, 1)
    }

    @Test
    fun `istupakov v3 layout resolves standard names`() {
        val out = resolveDecoderOutputs(
            listOf(
                "outputs" to LOGITS,
                "prednet_lengths" to PREP_LEN,
                "output_states_1" to STATES,
                "output_states_2" to STATES,
            ),
            BLANK,
        )
        assertThat(out).isEqualTo(ResolvedDecoderOutputs("outputs", "output_states_1", "output_states_2"))
    }

    @Test
    fun `orukeet layout resolves the same names despite reordered outputs`() {
        // Real orukeet dump: prednet_lengths at index 0, logits LAST.
        val out = resolveDecoderOutputs(
            listOf(
                "prednet_lengths" to PREP_LEN,
                "output_states_1" to STATES,
                "output_states_2" to STATES,
                "outputs" to LOGITS,
            ),
            BLANK,
        )
        assertThat(out).isEqualTo(ResolvedDecoderOutputs("outputs", "output_states_1", "output_states_2"))
    }

    @Test
    fun `ultra layout ignores extra rank-4 heads after the logits`() {
        // Real Olicorne ultra dump: the standard four, then lse/topk/duration extras -
        // three of them rank 4. A naive 'first rank-4 output' would pick a head.
        val out = resolveDecoderOutputs(
            listOf(
                "outputs" to LOGITS,
                "prednet_lengths" to PREP_LEN,
                "output_states_1" to STATES,
                "output_states_2" to STATES,
                "lse_token" to LSE,
                "lse_duration" to LSE,
                "topk_logits" to TOPK,
                "topk_ids" to TOPK,
                "duration_logits" to DUR_HEAD,
            ),
            BLANK,
        )
        assertThat(out).isEqualTo(ResolvedDecoderOutputs("outputs", "output_states_1", "output_states_2"))
    }

    @Test
    fun `renamed logits falls back to rank-4 with largest last dim`() {
        // Hypothetical export without the conventional 'outputs' name: the vocab-width
        // head must win over the smaller top-K and duration heads, whatever their order.
        val out = resolveDecoderOutputs(
            listOf(
                "topk_logits" to TOPK,
                "joint_logits" to LOGITS,
                "duration_logits" to DUR_HEAD,
                "output_states_1" to STATES,
                "output_states_2" to STATES,
            ),
            BLANK,
        )
        assertThat(out.logits).isEqualTo("joint_logits")
    }

    @Test
    fun `renamed state outputs fall back to shape matching in graph order`() {
        val out = resolveDecoderOutputs(
            listOf(
                "outputs" to LOGITS,
                "prednet_lengths" to PREP_LEN,
                "hidden_layernorm" to longArrayOf(1, 1, 1024),
                "states_h" to STATES,
                "states_c" to STATES,
            ),
            BLANK,
        )
        assertThat(out).isEqualTo(ResolvedDecoderOutputs("outputs", "states_h", "states_c"))
    }

    @Test
    fun `graph without vocab-width rank-4 logits fails fast`() {
        assertThatThrownBy {
            resolveDecoderOutputs(
                listOf(
                    "prednet_lengths" to PREP_LEN,
                    "topk_logits" to TOPK,
                    "output_states_1" to STATES,
                    "output_states_2" to STATES,
                ),
                BLANK,
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("logits")
    }

    @Test
    fun `graph with fewer than two state outputs fails fast`() {
        assertThatThrownBy {
            resolveDecoderOutputs(
                listOf(
                    "outputs" to LOGITS,
                    "prednet_lengths" to PREP_LEN,
                    "output_states_1" to STATES,
                ),
                BLANK,
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("state")
    }
}
