package com.configdirector.openfeature.internal

import com.configdirector.ConfigDirectorContext
import com.google.common.truth.Truth.assertThat
import dev.openfeature.kotlin.sdk.ImmutableContext
import dev.openfeature.kotlin.sdk.Value
import org.junit.Test

class ContextMappingTest {

    @Test
    fun `sends the targeting key as the id`() {
        val context = ImmutableContext(targetingKey = "user-123", attributes = mapOf("id" to Value.String("other")))

        assertThat(context.toConfigDirectorContext().id).isEqualTo("user-123")
    }

    @Test
    fun `falls back to an id attribute when there is no targeting key`() {
        assertThat(ImmutableContext(attributes = mapOf("id" to Value.Integer(42))).toConfigDirectorContext().id)
            .isEqualTo("42")
        assertThat(ImmutableContext().toConfigDirectorContext()).isEqualTo(ConfigDirectorContext.empty())
    }

    @Test
    fun `sends a top-level kind as the context kind`() {
        val context = ImmutableContext(targetingKey = "42", attributes = mapOf("kind" to Value.String("device")))

        assertThat(context.toConfigDirectorContext().kind).isEqualTo("device")
    }

    @Test
    fun `reads a top-level kind that is a number as its text`() {
        val context = ImmutableContext(targetingKey = "42", attributes = mapOf("kind" to Value.Integer(42)))

        assertThat(context.toConfigDirectorContext().kind).isEqualTo("42")
    }

    @Test
    fun `keeps a kind inside the traits as a trait`() {
        val context = ImmutableContext(
            targetingKey = "42",
            attributes = mapOf("traits" to Value.Structure(mapOf("kind" to Value.String("device")))),
        )

        val mapped = context.toConfigDirectorContext()

        assertThat(mapped.kind).isNull()
        assertThat(mapped.traits).containsExactly("kind", "device")
    }

    @Test
    fun `sends the name, the traits and the anonymous flag`() {
        val context = ImmutableContext(
            targetingKey = "user-123",
            attributes = mapOf(
                "name" to Value.String("Ada"),
                "traits" to Value.Structure(
                    mapOf("plan" to Value.String("pro"), "tags" to Value.List(listOf(Value.String("a")))),
                ),
                "anonymous" to Value.Boolean(true),
                "ignored" to Value.String("nobody reads this"),
            ),
        )

        assertThat(context.toConfigDirectorContext()).isEqualTo(
            ConfigDirectorContext.build {
                id("user-123")
                name("Ada")
                traits(mapOf("plan" to "pro", "tags" to listOf("a")))
                anonymous(true)
            },
        )
    }

    @Test
    fun `leaves out what does not fit`() {
        val context = ImmutableContext(
            attributes = mapOf(
                "name" to Value.Structure(mapOf("first" to Value.String("Ada"))),
                "kind" to Value.List(listOf(Value.String("device"))),
                "traits" to Value.Structure(emptyMap()),
                "anonymous" to Value.String("yes"),
            ),
        )

        assertThat(context.toConfigDirectorContext()).isEqualTo(ConfigDirectorContext.empty())
    }
}
