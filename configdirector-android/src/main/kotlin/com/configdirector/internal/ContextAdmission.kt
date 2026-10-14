package com.configdirector.internal

import com.configdirector.ConfigDirectorContext

private const val CONTEXT_KIND_MAXIMUM_LENGTH = 64
private const val CONTEXT_KIND_SAMPLE_MAXIMUM_CHARACTERS = 128

internal sealed class ContextAdmission {
    data class Admitted(val context: ConfigDirectorContext?) : ContextAdmission()

    data class IgnoredForAnInvalidContextKind(val contextKindSample: String) : ContextAdmission()
}

internal fun admissionOf(context: ConfigDirectorContext?): ContextAdmission {
    val contextKind = context?.kind
    return if (contextKind == null || isValidContextKind(contextKind)) {
        ContextAdmission.Admitted(context)
    } else {
        ContextAdmission.IgnoredForAnInvalidContextKind(
            firstCharactersOf(contextKind, CONTEXT_KIND_SAMPLE_MAXIMUM_CHARACTERS),
        )
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
