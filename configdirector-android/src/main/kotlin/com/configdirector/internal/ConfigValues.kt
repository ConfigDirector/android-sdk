package com.configdirector.internal

import com.configdirector.EvaluationReason
import kotlin.math.ceil
import kotlin.math.floor

internal data class EvaluationResult<T>(
    val value: T,
    val valueId: String?,
    val usedDefault: Boolean,
    val reason: EvaluationReason,
) {
    companion object {
        fun <T> matched(value: T, valueId: String?): EvaluationResult<T> =
            EvaluationResult(value, valueId, usedDefault = false, reason = EvaluationReason.FOUND_MATCH)

        fun <T> usedDefault(value: T, reason: EvaluationReason): EvaluationResult<T> =
            EvaluationResult(value, valueId = null, usedDefault = true, reason = reason)
    }
}

/** Which configs a value type can be read from, and how a value it cannot read is reported. */
internal enum class ConfigValueKind(
    val sourceTypes: Set<ConfigType>,
    val unreadableValueReason: EvaluationReason,
) {
    /** Boolean configs, and any config whose value spells `true` or `false`. */
    BOOLEAN(
        setOf(ConfigType.BOOLEAN, ConfigType.STRING, ConfigType.CUSTOM),
        EvaluationReason.INVALID_BOOLEAN,
    ),

    /** Every config: each value is a string on the wire, including a JSON config's raw document. */
    STRING(ConfigType.entries.toSet(), EvaluationReason.TYPE_MISMATCH),

    /** JSON configs only: a JSON document is not something another type happens to spell. */
    JSON(setOf(ConfigType.JSON), EvaluationReason.INVALID_JSON),

    /** Numeric configs, and any config whose value spells a finite number. */
    NUMBER(
        setOf(
            ConfigType.INTEGER,
            ConfigType.FLOAT,
            ConfigType.ENUMERATION,
            ConfigType.STRING,
            ConfigType.CUSTOM,
        ),
        EvaluationReason.INVALID_NUMBER,
    ),
}

internal fun ConfigState.asBoolean(defaultValue: Boolean): EvaluationResult<Boolean> =
    read(defaultValue, ConfigValueKind.BOOLEAN) { rawValue ->
        when (rawValue.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

internal fun ConfigState.asString(defaultValue: String): EvaluationResult<String> =
    read(defaultValue, ConfigValueKind.STRING) { rawValue -> rawValue }

/** Truncates a value written as a decimal, so that a float config can still serve an `Int`. */
internal fun ConfigState.asInt(defaultValue: Int): EvaluationResult<Int> =
    read(defaultValue, ConfigValueKind.NUMBER) { rawValue ->
        rawValue.toIntOrNull() ?: rawValue.truncatedToIntOrNull()
    }

internal fun ConfigState.asDouble(defaultValue: Double): EvaluationResult<Double> =
    read(defaultValue, ConfigValueKind.NUMBER) { rawValue ->
        rawValue.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

internal fun ConfigState.asJsonObject(
    defaultValue: Map<String, Any?>,
): EvaluationResult<Map<String, Any?>> =
    read(defaultValue, ConfigValueKind.JSON) { jsonObject }

internal fun ConfigState.asJsonArray(defaultValue: List<Any?>): EvaluationResult<List<Any?>> =
    read(defaultValue, ConfigValueKind.JSON) { jsonArray }

private fun <T> ConfigState.read(
    defaultValue: T,
    kind: ConfigValueKind,
    readValue: (String) -> T?,
): EvaluationResult<T> {
    if (value.isNullOrEmpty()) {
        return EvaluationResult.usedDefault(defaultValue, EvaluationReason.VALUE_MISSING)
    }
    if (type !in kind.sourceTypes) {
        return EvaluationResult.usedDefault(defaultValue, EvaluationReason.TYPE_MISMATCH)
    }

    val parsed = readValue(value)
        ?: return EvaluationResult.usedDefault(defaultValue, kind.unreadableValueReason)

    return EvaluationResult.matched(parsed, valueId)
}

private fun String.truncatedToIntOrNull(): Int? {
    val parsed = toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
    val truncated = if (parsed < 0) ceil(parsed) else floor(parsed)
    return if (truncated in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {
        truncated.toInt()
    } else {
        null
    }
}
