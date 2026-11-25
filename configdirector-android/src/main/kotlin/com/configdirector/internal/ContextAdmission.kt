package com.configdirector.internal

import com.configdirector.ConfigDirectorContext
import org.json.JSONException
import org.json.JSONObject

private const val CONTEXT_KIND_MAXIMUM_LENGTH = 64
private const val CONTEXT_KIND_SAMPLE_MAXIMUM_CHARACTERS = 128
private const val IDENTIFIER_MAXIMUM_CHARACTERS = 256
private const val NAME_MAXIMUM_CHARACTERS = 256
private const val TRAITS_MAXIMUM_JSON_BYTES = 65_536
private const val BYTES_PER_KILOBYTE = 1_024

internal enum class ContextSizeLimit(val invalidContextReason: String, val explanation: String) {
    IDENTIFIER("identifier-too-long", "its id is longer than $IDENTIFIER_MAXIMUM_CHARACTERS characters"),
    NAME("name-too-long", "its name is longer than $NAME_MAXIMUM_CHARACTERS characters"),
    TRAITS("traits-too-large", "its traits are over ${TRAITS_MAXIMUM_JSON_BYTES / BYTES_PER_KILOBYTE} KB of JSON"),
}

internal sealed class ContextAdmission {
    data class Admitted(val context: ConfigDirectorContext?) : ContextAdmission()

    data class IgnoredForAnInvalidContextKind(val contextKindSample: String) : ContextAdmission()

    data class IgnoredOverASizeLimit(val passedLimit: ContextSizeLimit) : ContextAdmission()
}

internal fun admissionOf(context: ConfigDirectorContext?): ContextAdmission {
    val contextKind = context?.kind
    if (contextKind != null && !isValidContextKind(contextKind)) {
        return ContextAdmission.IgnoredForAnInvalidContextKind(
            firstCharactersOf(contextKind, CONTEXT_KIND_SAMPLE_MAXIMUM_CHARACTERS),
        )
    }

    val passedSizeLimit = context?.let(::firstSizeLimitPassedBy)
    return if (passedSizeLimit == null) {
        ContextAdmission.Admitted(context)
    } else {
        ContextAdmission.IgnoredOverASizeLimit(passedSizeLimit)
    }
}

private fun firstSizeLimitPassedBy(context: ConfigDirectorContext): ContextSizeLimit? = when {
    hasMoreCharactersThan(IDENTIFIER_MAXIMUM_CHARACTERS, context.id) -> ContextSizeLimit.IDENTIFIER
    hasMoreCharactersThan(NAME_MAXIMUM_CHARACTERS, context.name) -> ContextSizeLimit.NAME
    hasMoreJsonBytesThan(TRAITS_MAXIMUM_JSON_BYTES, context.traits) -> ContextSizeLimit.TRAITS
    else -> null
}

private fun hasMoreCharactersThan(maximumCharacters: Int, text: String?): Boolean =
    text != null && text.codePointCount(0, text.length) > maximumCharacters

private fun hasMoreJsonBytesThan(maximumBytes: Int, traits: Map<String, Any?>?): Boolean {
    if (traits == null) return false

    val json = try {
        compactJsonOf(traits)
    } catch (numberOutsideTheDoubleRange: JSONException) {
        return false
    }
    return json.toByteArray(Charsets.UTF_8).size > maximumBytes
}

private fun compactJsonOf(traits: Map<String, Any?>): String =
    StringBuilder().apply { writeCompactJson(traits) }.toString()

private fun StringBuilder.writeCompactJson(value: Any?) {
    when (value) {
        is String -> writeJsonString(value)
        is Map<*, *> -> {
            append('{')
            value.entries.forEachIndexed { index, (key, element) ->
                if (index > 0) append(',')
                writeJsonString(key.toString())
                append(':')
                writeCompactJson(element)
            }
            append('}')
        }
        is List<*> -> {
            append('[')
            value.forEachIndexed { index, element ->
                if (index > 0) append(',')
                writeCompactJson(element)
            }
            append(']')
        }
        is Number -> append(JSONObject.numberToString(value))
        else -> append(value)
    }
}

private fun isValidContextKind(contextKind: String): Boolean =
    contextKind.length in 1..CONTEXT_KIND_MAXIMUM_LENGTH && contextKind.all(::isContextKindCharacter)

private fun isContextKindCharacter(character: Char): Boolean =
    character in 'a'..'z' || character in '0'..'9' || character == '-' || character == '_'

private fun firstCharactersOf(text: String, maximumCharacters: Int): String =
    if (text.codePointCount(0, text.length) <= maximumCharacters) {
        text
    } else {
        text.substring(0, text.offsetByCodePoints(0, maximumCharacters))
    }
