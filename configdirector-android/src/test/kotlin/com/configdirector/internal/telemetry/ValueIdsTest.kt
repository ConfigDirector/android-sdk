package com.configdirector.internal.telemetry

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ValueIdsTest {

    // Derived from the algorithm every ConfigDirector SDK implements -- the first 16 bytes of the
    // SHA-256 digest, base62, zero-padded to 22 -- rather than from this implementation, so the two
    // agreeing means the port is right.
    private val knownIds = mapOf(
        "" to "6ve2WrOl3mnciB6WIL2fIa",
        "true" to "5WwjWyUjJCIPKe8JswMQVH",
        """{"primary":"#101010"}""" to "5qnANxRDo77WQKTu3y85kW",
        "a".repeat(600) to "5fN8d72HXaUK6VkcOwuKTN",
        "Hello, Ada" to "3p5AqSKttaVpRHqgl9liXU",
    )

    @Test
    fun `derives the id every other SDK derives for the same value`() {
        knownIds.forEach { (value, id) ->
            assertThat(valueIdFor(value)).isEqualTo(id)
        }
    }

    @Test
    fun `is always the same width, whatever the digest starts with`() {
        val ids = (0 until 200).map { valueIdFor("value-$it") }

        assertThat(ids.map { it.length }.toSet()).containsExactly(22)
    }

    @Test
    fun `gives different values different ids`() {
        assertThat(valueIdFor("one")).isNotEqualTo(valueIdFor("two"))
    }
}
