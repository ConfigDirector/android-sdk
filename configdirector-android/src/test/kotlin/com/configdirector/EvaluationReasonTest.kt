package com.configdirector

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EvaluationReasonTest {

    @Test
    fun `spells a skipped rollout the way the other SDKs do`() {
        assertThat(EvaluationReason.ROLLOUT_SKIPPED_NO_IDENTIFIER.wireName).isEqualTo("rollout-skipped-no-identifier")
    }
}
