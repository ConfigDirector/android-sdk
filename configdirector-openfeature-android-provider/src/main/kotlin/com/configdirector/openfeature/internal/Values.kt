package com.configdirector.openfeature.internal

import com.configdirector.TrackOptions
import dev.openfeature.kotlin.sdk.TrackingEventDetails
import dev.openfeature.kotlin.sdk.Value
import java.util.Date
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
internal fun Value.toAny(): Any? = when (this) {
    is Value.String -> string
    is Value.Boolean -> boolean
    is Value.Integer -> integer
    is Value.Double -> double
    is Value.Instant -> instant.toString()
    is Value.Structure -> structure.mapValues { (_, entry) -> entry.toAny() }
    is Value.List -> list.map { entry -> entry.toAny() }
    Value.Null -> null
}

internal fun TrackingEventDetails.toTrackOptions(): TrackOptions = TrackOptions(
    value = value,
    details = structure.asMap()
        .takeIf { it.isNotEmpty() }
        ?.mapValues { (_, attribute) -> attribute.toTrackedEventDetailValue() },
)

@OptIn(ExperimentalTime::class)
private fun Value.toTrackedEventDetailValue(): Any? = when (this) {
    is Value.Instant -> Date(instant.toEpochMilliseconds())
    is Value.Structure -> structure.mapValues { (_, entry) -> entry.toTrackedEventDetailValue() }
    is Value.List -> list.map { entry -> entry.toTrackedEventDetailValue() }
    else -> toAny()
}

internal fun Any?.toValue(): Value = when (this) {
    null -> Value.Null
    is String -> Value.String(this)
    is Boolean -> Value.Boolean(this)
    is Int -> Value.Integer(this)
    is Long -> if (this in Int.MIN_VALUE..Int.MAX_VALUE) Value.Integer(toInt()) else Value.Double(toDouble())
    is Number -> Value.Double(toDouble())
    is Map<*, *> -> Value.Structure(entries.associate { (key, entry) -> key.toString() to entry.toValue() })
    is List<*> -> Value.List(map { entry -> entry.toValue() })
    else -> Value.String(toString())
}
