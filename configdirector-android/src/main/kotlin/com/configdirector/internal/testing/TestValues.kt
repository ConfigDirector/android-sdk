package com.configdirector.internal.testing

import com.configdirector.ConfigDirectorValidationException
import com.configdirector.internal.ConfigState
import com.configdirector.internal.ConfigType
import com.configdirector.internal.telemetry.valueIdFor
import java.math.BigDecimal
import java.util.SortedMap
import java.util.UUID

internal fun encodeTestValues(values: Map<String, Any>): Map<String, ConfigState> =
    values.entries.associate { (key, value) -> key to encodeTestValue(key, value) }

internal fun encodeTestValue(key: String, value: Any): ConfigState {
    if (key.isBlank()) {
        throw ConfigDirectorValidationException("Invalid config key '$key'. The key must not be blank.")
    }

    val (type, text) = when (value) {
        is Boolean -> ConfigType.BOOLEAN to value.toString()
        is Int, is Long -> ConfigType.INTEGER to value.toString()
        is Float -> ConfigType.FLOAT to plainDecimal(key, value.isFinite(), value.toString())
        is Double -> ConfigType.FLOAT to plainDecimal(key, value.isFinite(), value.toString())
        is String -> ConfigType.STRING to value
        is Map<*, *>, is List<*> -> ConfigType.JSON to jsonText(key, value)
        else -> throw ConfigDirectorValidationException(
            "Invalid value for '$key' of type ${value.javaClass.name}. A value must be a Boolean, " +
                "an Int, a Long, a Float, a Double, a String, a Map with String keys, or a List.",
        )
    }

    return ConfigState(
        key = key,
        type = type,
        value = text,
        id = UUID.randomUUID().toString(),
        valueId = valueIdFor(text),
    )
}

private fun plainDecimal(key: String, isFinite: Boolean, text: String): String {
    if (!isFinite) {
        throw ConfigDirectorValidationException("Invalid value for '$key'. A number must be finite.")
    }
    return if ('E' in text) BigDecimal(text).stripTrailingZeros().toPlainString() else text
}

private fun jsonText(key: String, value: Any): String =
    StringBuilder().apply { writeJson(key, value) }.toString()

private fun StringBuilder.writeJson(key: String, value: Any?) {
    when (value) {
        null -> append("null")
        is Boolean, is Int, is Long -> append(value)
        is Float -> append(plainDecimal(key, value.isFinite(), value.toString()))
        is Double -> append(plainDecimal(key, value.isFinite(), value.toString()))
        is String -> writeJsonString(value)
        is Map<*, *> -> writeJsonObject(key, value)
        is List<*> -> writeJsonArray(key, value)
        else -> throw ConfigDirectorValidationException(
            "Invalid value for '$key'. A JSON document may only contain null, Boolean, Int, Long, " +
                "Float, Double, String, Map with String keys, and List values, found " +
                "${value.javaClass.name}.",
        )
    }
}

private fun StringBuilder.writeJsonObject(key: String, map: Map<*, *>) {
    append('{')
    orderedEntries(key, map).forEachIndexed { index, (name, element) ->
        if (index > 0) append(',')
        writeJsonString(name)
        append(':')
        writeJson(key, element)
    }
    append('}')
}

private fun StringBuilder.writeJsonArray(key: String, list: List<*>) {
    append('[')
    list.forEachIndexed { index, element ->
        if (index > 0) append(',')
        writeJson(key, element)
    }
    append(']')
}

private fun orderedEntries(key: String, map: Map<*, *>): List<Pair<String, Any?>> {
    val entries = map.entries.map { (name, element) ->
        val stringName = name as? String ?: throw ConfigDirectorValidationException(
            "Invalid value for '$key'. Every key of a JSON object must be a String, found " +
                "${name?.javaClass?.name}.",
        )
        stringName to element
    }
    return if (map is LinkedHashMap<*, *> || map is SortedMap<*, *>) entries else entries.sortedBy { it.first }
}

private fun StringBuilder.writeJsonString(text: String) {
    append('"')
    for (character in text) {
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            else -> if (character < ' ') {
                append("\\u").append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
    append('"')
}
