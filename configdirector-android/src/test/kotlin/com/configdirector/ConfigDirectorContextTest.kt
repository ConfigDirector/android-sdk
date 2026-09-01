package com.configdirector

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ConfigDirectorContextTest {

    @Test
    fun `builds a context from the builder`() {
        val context = ConfigDirectorContext.builder()
            .id("user-123")
            .name("Ada")
            .trait("plan", "pro")
            .trait("seats", 12)
            .anonymous(true)
            .build()

        assertThat(context.id).isEqualTo("user-123")
        assertThat(context.name).isEqualTo("Ada")
        assertThat(context.traits).containsExactly("plan", "pro", "seats", 12)
        assertThat(context.isAnonymous).isTrue()
    }

    @Test
    fun `builds a context from the Kotlin DSL`() {
        val context = ConfigDirectorContext.build {
            id("user-123")
            traits(mapOf("beta" to true))
        }

        assertThat(context.id).isEqualTo("user-123")
        assertThat(context.traits).containsExactly("beta", true)
    }

    @Test
    fun `carries nothing when empty`() {
        val context = ConfigDirectorContext.empty()

        assertThat(context.id).isNull()
        assertThat(context.name).isNull()
        assertThat(context.traits).isNull()
        assertThat(context.isAnonymous).isFalse()
    }

    @Test
    fun `traits set at once replace the ones set so far`() {
        val context = ConfigDirectorContext.builder()
            .trait("plan", "pro")
            .traits(mapOf("beta" to true))
            .build()

        assertThat(context.traits).containsExactly("beta", true)
    }

    @Test
    fun `clears traits set so far`() {
        val context = ConfigDirectorContext.builder()
            .trait("plan", "pro")
            .traits(null)
            .build()

        assertThat(context.traits).isNull()
    }

    @Test
    fun `copies traits so that reusing the builder leaves a built context alone`() {
        val builder = ConfigDirectorContext.builder().trait("plan", "pro")
        val context = builder.build()

        builder.trait("plan", "free")

        assertThat(context.traits).containsExactly("plan", "pro")
        assertThat(builder.build().traits).containsExactly("plan", "free")
    }

    @Test
    fun `copies traits so that mutating the source map leaves the context alone`() {
        val traits = mutableMapOf<String, Any?>("plan" to "pro")
        val context = ConfigDirectorContext.builder().traits(traits).build()

        traits["plan"] = "free"

        assertThat(context.traits).containsExactly("plan", "pro")
    }

    @Test
    fun `hands out traits that cannot be modified`() {
        val context = ConfigDirectorContext.builder().trait("plan", "pro").build()

        @Suppress("UNCHECKED_CAST")
        val traits = context.traits as MutableMap<String, Any?>

        assertThrows(UnsupportedOperationException::class.java) { traits["plan"] = "free" }
    }

    @Test
    fun `equals every context carrying the same values`() {
        val context = ConfigDirectorContext.build { id("user-123"); trait("plan", "pro") }
        val same = ConfigDirectorContext.build { id("user-123"); trait("plan", "pro") }
        val different = ConfigDirectorContext.build { id("user-123"); trait("plan", "free") }

        assertThat(context).isEqualTo(same)
        assertThat(context.hashCode()).isEqualTo(same.hashCode())
        assertThat(context).isNotEqualTo(different)
    }

    @Test
    fun `accepts JSON shaped traits`() {
        val context = ConfigDirectorContext.build {
            trait("plan", "pro")
            trait("seats", 12)
            trait("weight", 1.5)
            trait("beta", true)
            trait("regions", listOf("us-east", mapOf("eu" to listOf(1, null))))
            trait("absent", null)
        }

        assertThat(context.traits).hasSize(6)
    }

    @Test
    fun `accepts the same nested value more than once`() {
        val shared = listOf("us-east")

        val context = ConfigDirectorContext.build { trait("regions", listOf(shared, shared)) }

        assertThat(context.traits).containsKey("regions")
    }

    @Test
    fun `rejects a trait that no targeting rule could match`() {
        val builder = ConfigDirectorContext.builder().trait("joined", java.util.Date(0))

        val failure = assertThrows(ConfigDirectorValidationException::class.java) { builder.build() }

        assertThat(failure).hasMessageThat().contains("Invalid trait 'joined'")
        assertThat(failure).hasMessageThat().contains("java.util.Date")
    }

    @Test
    fun `reports where inside a trait the unusable value sits`() {
        val builder = ConfigDirectorContext.builder()
            .trait("regions", listOf(mapOf("eu" to java.util.Date(0))))

        val failure = assertThrows(ConfigDirectorValidationException::class.java) { builder.build() }

        assertThat(failure).hasMessageThat().contains("'regions[0].eu'")
    }

    @Test
    fun `rejects a map inside a trait that is not keyed by String`() {
        val builder = ConfigDirectorContext.builder().trait("seats", mapOf(1 to "one"))

        val failure = assertThrows(ConfigDirectorValidationException::class.java) { builder.build() }

        assertThat(failure).hasMessageThat().contains("must be keyed by String")
    }

    @Test
    fun `rejects a trait that contains itself`() {
        val cyclic = mutableListOf<Any?>()
        cyclic.add(cyclic)
        val builder = ConfigDirectorContext.builder().trait("regions", cyclic)

        val failure = assertThrows(ConfigDirectorValidationException::class.java) { builder.build() }

        assertThat(failure).hasMessageThat().contains("contains itself")
    }

    @Test
    fun `rejects a trait nested deeper than the SDK walks`() {
        var nested: Any? = "leaf"
        repeat(40) { nested = listOf(nested) }
        val builder = ConfigDirectorContext.builder().trait("deep", nested)

        val failure = assertThrows(ConfigDirectorValidationException::class.java) { builder.build() }

        assertThat(failure).hasMessageThat().contains("nest at most 32 levels")
    }

    @Test
    fun `rejects a trait holding a number JSON cannot spell`() {
        val builder = ConfigDirectorContext.builder().trait("score", Double.NaN)

        val failure = assertThrows(ConfigDirectorValidationException::class.java) { builder.build() }

        assertThat(failure).hasMessageThat().contains("Invalid trait 'score'")
        assertThat(failure).hasMessageThat().contains("NaN")
    }

    @Test
    fun `rejects an infinite number inside a trait`() {
        val builder = ConfigDirectorContext.builder()
            .trait("limits", mapOf("max" to Float.POSITIVE_INFINITY))

        val failure = assertThrows(ConfigDirectorValidationException::class.java) { builder.build() }

        assertThat(failure).hasMessageThat().contains("'limits.max'")
    }
}
