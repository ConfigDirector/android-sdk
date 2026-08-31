package com.configdirector

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ToolchainSmokeTest {
    @Test
    fun `carries the message`() {
        val exception = ConfigDirectorException("boom", null)

        assertThat(exception).hasMessageThat().isEqualTo("boom")
    }
}
