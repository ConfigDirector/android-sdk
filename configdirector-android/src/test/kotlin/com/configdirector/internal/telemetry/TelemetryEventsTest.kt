package com.configdirector.internal.telemetry

import com.configdirector.internal.ConfigType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TelemetryEventsTest {

    @Test
    fun `reports a value small enough to send as itself`() {
        val compacted = TelemetryValue(value = "true", type = ConfigType.BOOLEAN).compacted()

        assertThat(compacted.value).isEqualTo("true")
        assertThat(compacted.valueId).isNull()
    }

    @Test
    fun `reports a value too large to send by its id`() {
        val long = "a".repeat(TelemetryValue.MAX_VALUE_LENGTH + 1)

        val compacted = TelemetryValue(value = long, type = ConfigType.STRING).compacted()

        assertThat(compacted.value).isNull()
        assertThat(compacted.valueId).isEqualTo(ValueIds.generate(long))
    }

    @Test
    fun `reports a value of exactly the maximum length as itself`() {
        val atLimit = "a".repeat(TelemetryValue.MAX_VALUE_LENGTH)

        assertThat(TelemetryValue(value = atLimit).compacted().value).isEqualTo(atLimit)
    }

    @Test
    fun `reports a JSON document by its id however short it is`() {
        val document = """{"a":1}"""

        val compacted = TelemetryValue(value = document, type = ConfigType.JSON).compacted()

        assertThat(compacted.value).isNull()
        assertThat(compacted.valueId).isEqualTo(ValueIds.generate(document))
    }

    @Test
    fun `prefers the id the server sent over hashing the value`() {
        val compacted = TelemetryValue(
            value = """{"a":1}""",
            valueId = "from-the-server",
            type = ConfigType.JSON,
        ).compacted()

        assertThat(compacted.valueId).isEqualTo("from-the-server")
    }

    @Test
    fun `keeps a short value even when the server sent an id for it`() {
        val compacted = TelemetryValue(
            value = "true",
            valueId = "from-the-server",
            type = ConfigType.BOOLEAN,
        ).compacted()

        assertThat(compacted.value).isEqualTo("true")
        assertThat(compacted.valueId).isNull()
    }

    @Test
    fun `reports a config with no value by the id alone`() {
        val compacted = TelemetryValue(value = null, valueId = "from-the-server").compacted()

        assertThat(compacted.value).isNull()
        assertThat(compacted.valueId).isEqualTo("from-the-server")
    }

    @Test
    fun `carries no type once compacted`() {
        assertThat(TelemetryValue(value = "true", type = ConfigType.BOOLEAN).compacted().type).isNull()
    }

    @Test
    fun `renders a JSON default as JSON rather than as a Kotlin map`() {
        val value = TelemetryValue.of(mapOf("primary" to "#101010"), null, ConfigType.JSON)

        assertThat(value.value).isEqualTo("""{"primary":"#101010"}""")
    }

    @Test
    fun `names the type the caller asked for the way the other SDKs do`() {
        assertThat(requestedTypeOf(true)).isEqualTo("Boolean")
        assertThat(requestedTypeOf("text")).isEqualTo("String")
        assertThat(requestedTypeOf(1)).isEqualTo("Integer")
        assertThat(requestedTypeOf(1.5)).isEqualTo("Double")
        assertThat(requestedTypeOf(emptyMap<String, Any?>())).isEqualTo("Map")
        assertThat(requestedTypeOf(emptyList<Any?>())).isEqualTo("List")
    }

    @Test
    fun `spells a timestamp the way the server parses it`() {
        // 2026-08-31T00:00:00.123Z
        assertThat(telemetryTimestamp(1788134400123L)).isEqualTo("2026-08-31T00:00:00.123Z")
    }

    @Test
    fun `collapses identical evaluations and keeps different ones apart`() {
        val read = evaluation(key = "dark-mode")
        val other = evaluation(key = "welcome-message")

        val aggregated = EventQueueSnapshot(1, 2, listOf(read, read, other), 0).aggregated()

        assertThat(aggregated).hasSize(2)
        assertThat(aggregated.single { it.event.key == "dark-mode" }.count).isEqualTo(2)
        assertThat(aggregated.single { it.event.key == "welcome-message" }.count).isEqualTo(1)
    }

    private fun evaluation(key: String) = EvaluatedConfigEvent(
        contextId = "user-123",
        key = key,
        type = ConfigType.BOOLEAN,
        defaultValue = TelemetryValue(value = "false"),
        requestedType = "Boolean",
        evaluatedValue = TelemetryValue(value = "true"),
        evaluatedValueId = "v1",
        usedDefault = false,
        evaluationReason = com.configdirector.EvaluationReason.FOUND_MATCH,
    )
}
