package com.configdirector.openfeature.internal

import com.configdirector.ConfigDirectorContext
import dev.openfeature.kotlin.sdk.EvaluationContext
import dev.openfeature.kotlin.sdk.Value

private const val ID = "id"
private const val NAME = "name"
private const val TRAITS = "traits"
private const val ANONYMOUS = "anonymous"

internal fun EvaluationContext.toConfigDirectorContext(): ConfigDirectorContext =
    ConfigDirectorContext.build {
        id(getTargetingKey().ifEmpty { null } ?: getValue(ID).asText())
        name(getValue(NAME).asText())
        getValue(TRAITS)?.asStructure()?.takeIf { it.isNotEmpty() }?.let { structure ->
            traits(structure.mapValues { (_, entry) -> entry.toAny() })
        }
        getValue(ANONYMOUS)?.asBoolean()?.let { anonymous(it) }
    }

private fun Value?.asText(): String? = when (this) {
    null, Value.Null, is Value.Structure, is Value.List -> null
    else -> toAny().toString()
}
