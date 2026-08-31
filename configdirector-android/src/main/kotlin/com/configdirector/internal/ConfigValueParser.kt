package com.configdirector.internal

import com.configdirector.EvaluationReason

internal class EvaluationResult<T>(
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

internal object ConfigValueParser {

    fun parseBoolean(configState: ConfigState, defaultValue: Boolean): EvaluationResult<Boolean> =
        parse(configState, defaultValue, ConfigValueKind.BOOLEAN) { rawValue ->
            when (rawValue.lowercase()) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }

    fun parseString(configState: ConfigState, defaultValue: String): EvaluationResult<String> =
        parse(configState, defaultValue, ConfigValueKind.STRING) { rawValue -> rawValue }

    /** Truncates a value written as a decimal, so that a float config can still serve an `Int`. */
    fun parseInt(configState: ConfigState, defaultValue: Int): EvaluationResult<Int> =
        parse(configState, defaultValue, ConfigValueKind.NUMBER) { rawValue ->
            rawValue.toIntOrNull() ?: truncateToInt(rawValue)
        }

    fun parseDouble(configState: ConfigState, defaultValue: Double): EvaluationResult<Double> =
        parse(configState, defaultValue, ConfigValueKind.NUMBER) { rawValue ->
            rawValue.toDoubleOrNull()?.takeIf { it.isFinite() }
        }

    private fun <T> parse(
        configState: ConfigState,
        defaultValue: T,
        kind: ConfigValueKind,
        read: (String) -> T?,
    ): EvaluationResult<T> {
        val rawValue = configState.value
        if (rawValue.isNullOrEmpty()) {
            return EvaluationResult.usedDefault(defaultValue, EvaluationReason.VALUE_MISSING)
        }
        if (configState.type !in kind.sourceTypes) {
            return EvaluationResult.usedDefault(defaultValue, EvaluationReason.TYPE_MISMATCH)
        }

        val value = read(rawValue)
            ?: return EvaluationResult.usedDefault(defaultValue, kind.unreadableValueReason)

        return EvaluationResult.matched(value, configState.valueId)
    }

    private fun truncateToInt(rawValue: String): Int? {
        val parsed = rawValue.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        val truncated = if (parsed < 0) kotlin.math.ceil(parsed) else kotlin.math.floor(parsed)
        return if (truncated in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {
            truncated.toInt()
        } else {
            null
        }
    }
}
