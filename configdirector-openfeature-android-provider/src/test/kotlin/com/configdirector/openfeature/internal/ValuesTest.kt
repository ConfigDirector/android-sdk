package com.configdirector.openfeature.internal

import com.google.common.truth.Truth.assertThat
import dev.openfeature.kotlin.sdk.Value
import org.junit.Test
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class ValuesTest {

    @OptIn(ExperimentalTime::class)
    @Test
    fun `turns an OpenFeature value into what a trait or a default can hold`() {
        val value = Value.Structure(
            mapOf(
                "text" to Value.String("a"),
                "flag" to Value.Boolean(true),
                "count" to Value.Integer(4),
                "ratio" to Value.Double(0.5),
                "when" to Value.Instant(Instant.parse("2026-08-31T00:00:00Z")),
                "nothing" to Value.Null,
                "items" to Value.List(listOf(Value.Integer(1), Value.Structure(mapOf("deep" to Value.String("b"))))),
            ),
        )

        assertThat(value.toAny()).isEqualTo(
            mapOf(
                "text" to "a",
                "flag" to true,
                "count" to 4,
                "ratio" to 0.5,
                "when" to "2026-08-31T00:00:00Z",
                "nothing" to null,
                "items" to listOf(1, mapOf("deep" to "b")),
            ),
        )
    }

    @Test
    fun `turns a parsed JSON document into an OpenFeature value`() {
        val document = mapOf(
            "text" to "a",
            "flag" to false,
            "count" to 4,
            "big" to 5_000_000_000L,
            "small" to 7L,
            "ratio" to 0.5,
            "nothing" to null,
            "items" to listOf("x", mapOf("deep" to 1)),
        )

        assertThat(document.toValue()).isEqualTo(
            Value.Structure(
                mapOf(
                    "text" to Value.String("a"),
                    "flag" to Value.Boolean(false),
                    "count" to Value.Integer(4),
                    "big" to Value.Double(5_000_000_000.0),
                    "small" to Value.Integer(7),
                    "ratio" to Value.Double(0.5),
                    "nothing" to Value.Null,
                    "items" to Value.List(listOf(Value.String("x"), Value.Structure(mapOf("deep" to Value.Integer(1))))),
                ),
            ),
        )
    }
}
