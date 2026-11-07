package com.configdirector.openfeature.internal

import com.configdirector.EvaluationReason
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ResolutionsTest {

    @Test
    fun `resolves a skipped rollout with the reason DEFAULT and the variant it was given`() {
        val evaluation = providerEvaluationFor(
            "checkout-cta",
            EvaluationReason.ROLLOUT_SKIPPED_NO_IDENTIFIER,
            "control",
            "Start free trial",
        )

        assertThat(evaluation.value).isEqualTo("Start free trial")
        assertThat(evaluation.variant).isEqualTo("control")
        assertThat(evaluation.reason).isEqualTo("DEFAULT")
        assertThat(evaluation.errorCode).isNull()
    }
}
