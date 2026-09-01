package com.configdirector.internal.telemetry

import com.configdirector.EvaluationReason
import com.configdirector.internal.ConfigType
import com.configdirector.internal.toJsonText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject

/**
 * One side of an evaluation -- what was asked for, or what came back -- as telemetry reports it:
 * the value spelled out, or the id of a value too large to spell out.
 */
internal data class TelemetryValue(
    /**
     * The value as the caller holds it, which is a map or a list for a JSON config. It is spelled
     * out as text only when the value is [compacted].
     */
    val value: Any? = null,
    val valueId: String? = null,
    /** The type the config was declared with, carried only until the value is [compacted]. */
    val type: ConfigType? = null,
) {
    /**
     * The form that goes on the wire: an oversized value, and every JSON document, is replaced by
     * its id. This is the only step that spells values out and hashes them, which is why it runs
     * off the caller's thread.
     */
    fun compacted(): TelemetryValue {
        val isJson = type == ConfigType.JSON
        if (valueId != null && isJson) return TelemetryValue(valueId = valueId)

        val text = value?.toJsonText()
        val mustUseId = isJson || (text?.length ?: 0) > MAX_VALUE_LENGTH

        if (valueId != null && mustUseId) return TelemetryValue(valueId = valueId)
        if (text.isNullOrEmpty()) return TelemetryValue(valueId = valueId)

        return if (mustUseId) {
            TelemetryValue(valueId = valueIdFor(text))
        } else {
            TelemetryValue(value = text)
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        value?.let { put("value", it.toJsonText()) }
        valueId?.let { put("valueId", it) }
        type?.let { put("type", it.wireName) }
    }

    companion object {
        /** Values longer than this are reported by id rather than inline, to keep payloads small. */
        const val MAX_VALUE_LENGTH: Int = 500

        fun of(value: Any?, valueId: String?, type: ConfigType?): TelemetryValue =
            TelemetryValue(value = value.toJsonText(), valueId = valueId, type = type)
    }
}

/**
 * A single config evaluation, as reported to ConfigDirector.
 *
 * Identical evaluations are reported once with a count, so equality is what decides which events
 * collapse together.
 */
internal data class EvaluatedConfigEvent(
    val contextId: String?,
    val key: String,
    /** The type the config was declared with, or null when no state was found for [key]. */
    val type: ConfigType?,
    val defaultValue: TelemetryValue,
    /** The name of the type the caller asked the value to be returned as. */
    val requestedType: String,
    val evaluatedValue: TelemetryValue,
    /**
     * The id the server sent for the evaluated value, kept alongside [evaluatedValue] because a
     * value small enough to report inline is reported by value.
     */
    val evaluatedValueId: String?,
    val usedDefault: Boolean,
    val evaluationReason: EvaluationReason,
) {
    fun compacted(): EvaluatedConfigEvent =
        copy(defaultValue = defaultValue.compacted(), evaluatedValue = evaluatedValue.compacted())

    fun toJson(): JSONObject = JSONObject().apply {
        contextId?.let { put("contextId", it) }
        put("key", key)
        type?.let { put("type", it.wireName) }
        put("defaultValue", defaultValue.toJson())
        put("requestedType", requestedType)
        put("evaluatedValue", evaluatedValue.toJson())
        evaluatedValueId?.let { put("evaluatedValueId", it) }
        put("usedDefault", usedDefault)
        put("evaluationReason", evaluationReason.wireName)
    }
}

/** A group of identical events, reported once with the number of times it occurred. */
internal data class AggregatedEvent(
    val startTime: Long,
    val endTime: Long,
    val count: Int,
    val event: EvaluatedConfigEvent,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("startTime", telemetryTimestamp(startTime))
        .put("endTime", telemetryTimestamp(endTime))
        .put("count", count)
        .put("event", event.toJson())
}

/** The name the ConfigDirector SDKs report for the type a value was asked for. */
internal fun requestedTypeOf(defaultValue: Any?): String = when (defaultValue) {
    null -> "null"
    is Map<*, *> -> "Map"
    is List<*> -> "List"
    else -> defaultValue.javaClass.simpleName
}

/**
 * RFC 3339 in UTC to the millisecond, which is what the other SDKs send and the server parses.
 *
 * `java.time` is API 26, and the SDK runs on 21 without asking consumers to turn on desugaring.
 */
internal fun telemetryTimestamp(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(millis))
