package com.configdirector

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VariationTest {

    @Test
    fun `compares by id and name`() {
        val first = Variation("variation-1", "treatment-a")
        val second = Variation("variation-1", "treatment-a")

        assertThat(first).isEqualTo(second)
        assertThat(first.hashCode()).isEqualTo(second.hashCode())
    }

    @Test
    fun `tells variations apart by id`() {
        assertThat(Variation("variation-1", "treatment-a")).isNotEqualTo(Variation("variation-2", "treatment-a"))
    }

    @Test
    fun `tells a named variation apart from an unnamed one`() {
        assertThat(Variation("variation-1", "treatment-a")).isNotEqualTo(Variation("variation-1"))
    }
}
