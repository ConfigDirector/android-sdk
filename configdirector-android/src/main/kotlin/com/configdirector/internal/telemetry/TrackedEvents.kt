package com.configdirector.internal.telemetry

import android.annotation.SuppressLint
import com.configdirector.ConfigDirectorContext
import com.configdirector.TrackOptions
import com.configdirector.internal.DEFAULT_CONTEXT_KIND
import com.configdirector.internal.writeJsonString
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.Collections
import java.util.Date

internal const val TRACKED_EVENTS_PER_BATCH: Int = 1_000

private const val NAME_MAXIMUM_CHARACTERS = 150
private const val DETAILS_MAXIMUM_TOP_LEVEL_KEYS = 20
private const val DETAILS_MAXIMUM_JSON_BYTES = 1_024
private const val LEAST_JSON_BYTES_PER_VALUE = 2
private const val MOST_VALUES_THAT_FIT_THE_JSON_LIMIT =
    (DETAILS_MAXIMUM_JSON_BYTES + 1) / LEAST_JSON_BYTES_PER_VALUE

internal data class TrackedEventEntry(
    val name: String,
    val contextKind: String?,
    val contextIdentifier: String?,
    val value: Number?,
    val details: Map<String, Any?>?,
    val happenedAtMillis: Long,
) {
    fun toJsonText(): String = jsonTextOf(
        buildMap<String, Any?> {
            put("name", name)
            contextKind?.let { put("contextKind", it) }
            contextIdentifier?.let { put("contextIdentifier", it) }
            value?.let { put("value", it) }
            details?.let { put("details", it) }
            put("happenedAt", telemetryTimestamp(happenedAtMillis))
        },
    )
}

internal enum class TrackedEventWarning(val message: String) {
    NAME_OUTSIDE_THE_CHARACTER_LIMIT(
        "[ConfigDirectorClient] A tracked event was not reported because its name does not have 1 to " +
            "$NAME_MAXIMUM_CHARACTERS characters.",
    ),
    VALUE_NOT_A_FINITE_NUMBER(
        "[ConfigDirectorClient] A tracked event was reported without its value because the value is " +
            "not a finite number.",
    ),
    DETAILS_NOT_REPORTABLE(
        "[ConfigDirectorClient] A tracked event was reported without its details because they have " +
            "more than $DETAILS_MAXIMUM_TOP_LEVEL_KEYS top-level keys, are larger than " +
            "$DETAILS_MAXIMUM_JSON_BYTES bytes as JSON, or cannot be written as JSON.",
    ),
    BATCH_ALREADY_FULL(
        "[ConfigDirectorClient] A tracked event was not reported because $TRACKED_EVENTS_PER_BATCH " +
            "tracked events are already waiting for the next telemetry batch.",
    ),
}

internal sealed class TrackedEventAdmission {
    abstract val warnings: List<TrackedEventWarning>

    data class Admitted(
        val entry: TrackedEventEntry,
        val context: ConfigDirectorContext?,
        override val warnings: List<TrackedEventWarning>,
    ) : TrackedEventAdmission()

    data class Dropped(override val warnings: List<TrackedEventWarning>) : TrackedEventAdmission()
}

internal fun admitTrackedEvent(
    name: String,
    context: ConfigDirectorContext?,
    options: TrackOptions?,
    happenedAtMillis: Long,
): TrackedEventAdmission {
    if (!isWithinTheCharacterLimit(name)) {
        return TrackedEventAdmission.Dropped(listOf(TrackedEventWarning.NAME_OUTSIDE_THE_CHARACTER_LIMIT))
    }

    val reportedContext = reportedContextOf(context)
    val value = reportableValueOf(options?.value)
    val details = reportableDetailsOf(options?.details)
    return TrackedEventAdmission.Admitted(
        TrackedEventEntry(
            name = name,
            contextKind = reportedContext?.kind,
            contextIdentifier = reportedContext?.identifier,
            value = value.fieldOrNull(),
            details = details.fieldOrNull(),
            happenedAtMillis = happenedAtMillis,
        ),
        context,
        listOfNotNull(value.warningOrNull(), details.warningOrNull()),
    )
}

private class ReportedContext(val kind: String, val identifier: String)

private sealed class ReportableOption<out T> {
    object NotGiven : ReportableOption<Nothing>()

    class Reportable<T>(val field: T) : ReportableOption<T>()

    class LeftOut(val warning: TrackedEventWarning) : ReportableOption<Nothing>()

    fun fieldOrNull(): T? = (this as? Reportable)?.field

    fun warningOrNull(): TrackedEventWarning? = (this as? LeftOut)?.warning
}

private fun isWithinTheCharacterLimit(name: String): Boolean =
    name.isNotEmpty() && name.codePointCount(0, name.length) <= NAME_MAXIMUM_CHARACTERS

private fun reportedContextOf(context: ConfigDirectorContext?): ReportedContext? {
    if (context == null || context.isAnonymous) return null

    val identifier = context.id ?: return null
    return ReportedContext(context.kind ?: DEFAULT_CONTEXT_KIND, identifier)
}

private fun reportableValueOf(value: Number?): ReportableOption<Number> {
    if (value == null) return ReportableOption.NotGiven

    val writtenNumber = try {
        writtenNumberOf(value)
    } catch (notANumber: Exception) {
        null
    }
    return if (writtenNumber != null && isFinite(writtenNumber)) {
        ReportableOption.Reportable(writtenNumber)
    } else {
        ReportableOption.LeftOut(TrackedEventWarning.VALUE_NOT_A_FINITE_NUMBER)
    }
}

private fun reportableDetailsOf(details: Map<String, Any?>?): ReportableOption<Map<String, Any?>> {
    if (details == null) return ReportableOption.NotGiven

    val copy = jsonCopyWithinTheLimits(details)
        ?: return ReportableOption.LeftOut(TrackedEventWarning.DETAILS_NOT_REPORTABLE)
    return ReportableOption.Reportable(copy)
}

private fun jsonCopyWithinTheLimits(details: Map<String, Any?>): Map<String, Any?>? = try {
    if (details.size > DETAILS_MAXIMUM_TOP_LEVEL_KEYS) {
        null
    } else {
        DetailsCopy().objectCopyOf(details).takeIf(::isWithinTheJsonLimit)
    }
} catch (notJsonShaped: Exception) {
    null
} catch (javaTimeIsAbsentBelowApi26: LinkageError) {
    null
}

private fun isWithinTheJsonLimit(details: Map<String, Any?>): Boolean =
    jsonTextOf(details).toByteArray(Charsets.UTF_8).size <= DETAILS_MAXIMUM_JSON_BYTES

private fun writtenNumberOf(number: Number): Number = when (number) {
    is Float -> number.toString().toDouble()
    is Double, is Long, is Int, is Short, is Byte, is BigInteger, is BigDecimal -> number
    else -> BigDecimal(number.toString())
}

private fun isFinite(number: Number): Boolean = number !is Double || number.isFinite()

private class DetailsCopy {

    private var valuesCopied = 0

    fun objectCopyOf(members: Map<*, *>): Map<String, Any?> {
        val copy = LinkedHashMap<String, Any?>()
        members.forEach { (key, value) ->
            require(key is String) { "A key in the details is not a String" }
            copy[key] = copyOf(value)
        }
        return Collections.unmodifiableMap(copy)
    }

    private fun arrayCopyOf(items: Collection<*>): List<Any?> =
        Collections.unmodifiableList(items.map(::copyOf))

    private fun copyOf(value: Any?): Any? {
        valuesCopied += 1
        require(valuesCopied <= MOST_VALUES_THAT_FIT_THE_JSON_LIMIT) {
            "The details hold more values than fit the JSON limit"
        }

        return when (value) {
            null, is String, is Boolean -> value
            is Number -> writtenNumberOf(value).takeIf(::isFinite)
            is Date -> telemetryTimestamp(value.time)
            is Map<*, *> -> objectCopyOf(value)
            is Collection<*> -> arrayCopyOf(value)
            else -> JavaTimeMoments.isoTextOf(value)
        }
    }
}

@SuppressLint("NewApi")
private object JavaTimeMoments {
    fun isoTextOf(value: Any): String = when (value) {
        is Instant -> telemetryTimestamp(value.toEpochMilli())
        is OffsetDateTime -> telemetryTimestamp(value.toInstant().toEpochMilli())
        is ZonedDateTime -> telemetryTimestamp(value.toInstant().toEpochMilli())
        else -> throw IllegalArgumentException("A value in the details has no JSON form")
    }
}

private fun jsonTextOf(value: Any?): String = StringBuilder().apply { writeTrackedEventJson(value) }.toString()

private fun StringBuilder.writeTrackedEventJson(value: Any?) {
    when (value) {
        is String -> writeJsonString(value)
        is Map<*, *> -> {
            append('{')
            value.entries.forEachIndexed { index, (key, element) ->
                if (index > 0) append(',')
                writeJsonString(key as String)
                append(':')
                writeTrackedEventJson(element)
            }
            append('}')
        }
        is List<*> -> {
            append('[')
            value.forEachIndexed { index, element ->
                if (index > 0) append(',')
                writeTrackedEventJson(element)
            }
            append(']')
        }
        else -> append(value)
    }
}
