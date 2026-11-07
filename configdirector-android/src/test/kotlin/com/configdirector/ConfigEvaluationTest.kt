package com.configdirector

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ConfigEvaluationTest {

    @Test
    fun `tells apart evaluations that differ only by the variation`() {
        assertThat(evaluationServing(Variation("variation-1", "treatment-a")))
            .isNotEqualTo(evaluationServing(Variation("variation-2", "control")))
    }

    private fun evaluationServing(variation: Variation) = ConfigEvaluation(
        key = "checkout-cta",
        value = "Try it free",
        valueId = "checkout-cta-value",
        variation = variation,
        isDefaultValue = false,
        reason = EvaluationReason.FOUND_MATCH,
        context = null,
    )
}
