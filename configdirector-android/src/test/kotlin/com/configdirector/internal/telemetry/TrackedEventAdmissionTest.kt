package com.configdirector.internal.telemetry

import com.configdirector.ConfigDirectorContext
import com.configdirector.TrackOptions
import com.google.common.truth.Truth.assertThat
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.Date
import java.util.concurrent.atomic.AtomicLong
import org.junit.Test

class TrackedEventAdmissionTest {

    private val happenedAtMillis = 1_796_083_200_000L

    private fun admission(
        name: String = "checkout-completed",
        context: ConfigDirectorContext? = null,
        options: TrackOptions? = null,
    ): TrackedEventAdmission = admitTrackedEvent(name, context, options, happenedAtMillis)

    private fun admitted(
        name: String = "checkout-completed",
        context: ConfigDirectorContext? = null,
        options: TrackOptions? = null,
    ): TrackedEventAdmission.Admitted = admission(name, context, options) as TrackedEventAdmission.Admitted

    private fun admittedWithDetails(details: Map<String, Any?>): TrackedEventAdmission.Admitted =
        admitted(options = TrackOptions(details = details))

    private fun admittedWithValue(value: Number): TrackedEventAdmission.Admitted =
        admitted(options = TrackOptions(value = value))

    private class NumberWithText(private val text: () -> String) : Number() {
        override fun toByte(): Byte = 0
        override fun toDouble(): Double = 0.0
        override fun toFloat(): Float = 0f
        override fun toInt(): Int = 0
        override fun toLong(): Long = 0
        override fun toShort(): Short = 0
        override fun toString(): String = text()
    }

    private class NoJsonForm

    @Test
    fun `writes the entry with its name, context, value, details and time`() {
        val entry = admitted(
            context = ConfigDirectorContext.build { id("user-123") },
            options = TrackOptions(value = 49.9, details = mapOf("plan" to "pro")),
        ).entry

        assertThat(entry.toJsonText()).isEqualTo(
            """{"name":"checkout-completed","contextKind":"user","contextIdentifier":"user-123",""" +
                """"value":49.9,"details":{"plan":"pro"},"happenedAt":"2026-12-01T00:00:00.000Z"}""",
        )
    }

    @Test
    fun `writes only the name and the time of an event without a context or options`() {
        assertThat(admitted().entry.toJsonText())
            .isEqualTo("""{"name":"checkout-completed","happenedAt":"2026-12-01T00:00:00.000Z"}""")
    }

    @Test
    fun `carries the kind of a context that has one`() {
        val entry = admitted(context = ConfigDirectorContext.build { id("device-9"); kind("device") }).entry

        assertThat(entry.contextKind).isEqualTo("device")
        assertThat(entry.contextIdentifier).isEqualTo("device-9")
    }

    @Test
    fun `carries no context for a context without an id, and no warning`() {
        val admission = admitted(context = ConfigDirectorContext.build { kind("device"); name("Ada") })

        assertThat(admission.entry.contextKind).isNull()
        assertThat(admission.entry.contextIdentifier).isNull()
        assertThat(admission.warnings).isEmpty()
    }

    @Test
    fun `withholds the identifier and the kind of a context marked anonymous`() {
        val admission = admitted(context = ConfigDirectorContext.build { id("user-123"); anonymous(true) })

        assertThat(admission.entry.contextKind).isNull()
        assertThat(admission.entry.contextIdentifier).isNull()
        assertThat(admission.warnings).isEmpty()
    }

    @Test
    fun `drops an event with an empty name`() {
        assertThat(admission(name = ""))
            .isEqualTo(TrackedEventAdmission.Dropped(listOf(TrackedEventWarning.NAME_OUTSIDE_THE_CHARACTER_LIMIT)))
    }

    @Test
    fun `admits a name of 150 characters`() {
        val name = "n".repeat(150)

        assertThat(admitted(name = name).entry.name).isEqualTo(name)
    }

    @Test
    fun `drops an event whose name has 151 characters`() {
        assertThat(admission(name = "n".repeat(151)))
            .isEqualTo(TrackedEventAdmission.Dropped(listOf(TrackedEventWarning.NAME_OUTSIDE_THE_CHARACTER_LIMIT)))
    }

    @Test
    fun `counts a character outside the basic plane as one`() {
        val name = "😀".repeat(150)

        assertThat(admitted(name = name).entry.name).isEqualTo(name)
    }

    @Test
    fun `writes a BigDecimal with every digit`() {
        val entry = admittedWithValue(BigDecimal("1.00000000000000000001")).entry

        assertThat(entry.toJsonText()).contains(""""value":1.00000000000000000001,""")
    }

    @Test
    fun `writes a BigDecimal with its trailing zeros`() {
        val entry = admittedWithValue(BigDecimal("49.90")).entry

        assertThat(entry.toJsonText()).contains(""""value":49.90,""")
    }

    @Test
    fun `writes a BigDecimal too large for a double`() {
        val entry = admittedWithValue(BigDecimal("1E+500")).entry

        assertThat(entry.toJsonText()).contains(""""value":1E+500,""")
    }

    @Test
    fun `writes a BigInteger with every digit`() {
        val entry = admittedWithValue(BigInteger("123456789012345678901234567890")).entry

        assertThat(entry.toJsonText()).contains(""""value":123456789012345678901234567890,""")
    }

    @Test
    fun `writes a Long with every digit`() {
        val entry = admittedWithValue(9_007_199_254_740_993L).entry

        assertThat(entry.toJsonText()).contains(""""value":9007199254740993,""")
    }

    @Test
    fun `writes a Float with the digits it prints`() {
        val entry = admittedWithValue(0.1f).entry

        assertThat(entry.toJsonText()).contains(""""value":0.1,""")
    }

    @Test
    fun `writes another kind of Number through its text`() {
        val entry = admittedWithValue(AtomicLong(42)).entry

        assertThat(entry.toJsonText()).contains(""""value":42,""")
    }

    @Test
    fun `leaves out a value that is not a number and keeps the event`() {
        val admission = admittedWithValue(Double.NaN)

        assertThat(admission.entry.value).isNull()
        assertThat(admission.warnings).containsExactly(TrackedEventWarning.VALUE_NOT_A_FINITE_NUMBER)
    }

    @Test
    fun `leaves out an infinite value and keeps the event`() {
        val admission = admittedWithValue(Double.POSITIVE_INFINITY)

        assertThat(admission.entry.value).isNull()
        assertThat(admission.warnings).containsExactly(TrackedEventWarning.VALUE_NOT_A_FINITE_NUMBER)
    }

    @Test
    fun `leaves out a Float that is not a number and keeps the event`() {
        val admission = admittedWithValue(Float.NaN)

        assertThat(admission.entry.value).isNull()
        assertThat(admission.warnings).containsExactly(TrackedEventWarning.VALUE_NOT_A_FINITE_NUMBER)
    }

    @Test
    fun `leaves out a Number whose text is not a number and keeps the event`() {
        val admission = admittedWithValue(NumberWithText { "not a number" })

        assertThat(admission.entry.value).isNull()
        assertThat(admission.warnings).containsExactly(TrackedEventWarning.VALUE_NOT_A_FINITE_NUMBER)
    }

    @Test
    fun `leaves out a Number whose text cannot be read and keeps the event`() {
        val admission = admittedWithValue(NumberWithText { throw IllegalStateException("unreadable") })

        assertThat(admission.entry.value).isNull()
        assertThat(admission.warnings).containsExactly(TrackedEventWarning.VALUE_NOT_A_FINITE_NUMBER)
    }

    @Test
    fun `writes strings, numbers, booleans, nulls, nested objects and arrays in the order given`() {
        val entry = admittedWithDetails(
            linkedMapOf(
                "plan" to "pro",
                "seats" to 3,
                "price" to 49.9,
                "trial" to false,
                "coupon" to null,
                "items" to listOf("a", 1),
                "billing" to mapOf("country" to "AR"),
            ),
        ).entry

        assertThat(entry.toJsonText()).contains(
            """"details":{"plan":"pro","seats":3,"price":49.9,"trial":false,"coupon":null,""" +
                """"items":["a",1],"billing":{"country":"AR"}},""",
        )
    }

    @Test
    fun `writes a collection that is not a list as an array`() {
        val entry = admittedWithDetails(mapOf("tags" to linkedSetOf("a", "b"))).entry

        assertThat(entry.toJsonText()).contains(""""details":{"tags":["a","b"]},""")
    }

    @Test
    fun `escapes quotes, backslashes and control characters in details`() {
        val entry = admittedWithDetails(mapOf("note" to "a\"b\\c\nd")).entry

        assertThat(entry.toJsonText()).contains(""""details":{"note":"a\"b\\c\nd"},""")
    }

    @Test
    fun `writes a number that is not finite inside details as null`() {
        val entry = admittedWithDetails(mapOf("ratio" to Double.NaN, "limit" to Float.POSITIVE_INFINITY)).entry

        assertThat(entry.toJsonText()).contains(""""details":{"ratio":null,"limit":null},""")
    }

    @Test
    fun `writes every digit of a BigDecimal inside details`() {
        val entry = admittedWithDetails(mapOf("amount" to BigDecimal("1.00000000000000000001"))).entry

        assertThat(entry.toJsonText()).contains(""""details":{"amount":1.00000000000000000001},""")
    }

    @Test
    fun `writes a Date inside details as ISO text`() {
        val entry = admittedWithDetails(mapOf("renews" to Date.from(Instant.parse("2026-12-01T00:00:00Z")))).entry

        assertThat(entry.details).containsExactly("renews", "2026-12-01T00:00:00.000Z")
    }

    @Test
    fun `writes an Instant inside details as ISO text`() {
        val entry = admittedWithDetails(mapOf("renews" to Instant.parse("2026-12-01T00:00:00Z"))).entry

        assertThat(entry.details).containsExactly("renews", "2026-12-01T00:00:00.000Z")
    }

    @Test
    fun `writes an OffsetDateTime inside details as ISO text in UTC`() {
        val entry = admittedWithDetails(mapOf("renews" to OffsetDateTime.parse("2026-12-01T01:00:00+01:00"))).entry

        assertThat(entry.details).containsExactly("renews", "2026-12-01T00:00:00.000Z")
    }

    @Test
    fun `writes a ZonedDateTime inside details as ISO text in UTC`() {
        val moment = ZonedDateTime.parse("2026-12-01T01:00:00+01:00[Europe/Paris]")

        val entry = admittedWithDetails(mapOf("renews" to moment)).entry

        assertThat(entry.details).containsExactly("renews", "2026-12-01T00:00:00.000Z")
    }

    @Test
    fun `writes a date nested inside details as ISO text`() {
        val entry = admittedWithDetails(
            mapOf("periods" to listOf(mapOf("ends" to Instant.parse("2026-12-01T00:00:00Z")))),
        ).entry

        assertThat(entry.toJsonText()).contains(""""details":{"periods":[{"ends":"2026-12-01T00:00:00.000Z"}]},""")
    }

    @Test
    fun `leaves out details holding a date without a time zone and keeps the event`() {
        val admission = admittedWithDetails(mapOf("renews" to LocalDate.parse("2026-12-01")))

        assertThat(admission.entry.details).isNull()
        assertThat(admission.warnings).containsExactly(TrackedEventWarning.DETAILS_NOT_REPORTABLE)
    }

    @Test
    fun `leaves out details holding a value with no JSON form and keeps the event`() {
        val admission = admittedWithDetails(mapOf("cart" to NoJsonForm()))

        assertThat(admission.entry.details).isNull()
        assertThat(admission.warnings).containsExactly(TrackedEventWarning.DETAILS_NOT_REPORTABLE)
    }

    @Test
    fun `leaves out details with a nested key that is not a string and keeps the event`() {
        val admission = admittedWithDetails(mapOf("prices" to mapOf(1 to "one")))

        assertThat(admission.entry.details).isNull()
        assertThat(admission.warnings).containsExactly(TrackedEventWarning.DETAILS_NOT_REPORTABLE)
    }

    @Test
    fun `leaves out details that contain themselves and keeps the event`() {
        val details = LinkedHashMap<String, Any?>()
        details["self"] = details

        val admission = admittedWithDetails(details)

        assertThat(admission.entry.details).isNull()
        assertThat(admission.warnings).containsExactly(TrackedEventWarning.DETAILS_NOT_REPORTABLE)
    }

    @Test
    fun `leaves out details that cannot be read and keeps the event`() {
        val details = object : AbstractMap<String, Any?>() {
            override val entries: Set<Map.Entry<String, Any?>>
                get() = throw IllegalStateException("unreadable")
        }

        val admission = admittedWithDetails(details)

        assertThat(admission.entry.details).isNull()
        assertThat(admission.warnings).containsExactly(TrackedEventWarning.DETAILS_NOT_REPORTABLE)
    }

    @Test
    fun `leaves out details when reading them needs a class this Android version lacks`() {
        val details = object : AbstractMap<String, Any?>() {
            override val entries: Set<Map.Entry<String, Any?>>
                get() = throw NoClassDefFoundError("java/time/Instant")
        }

        val admission = admittedWithDetails(details)

        assertThat(admission.entry.details).isNull()
        assertThat(admission.warnings).containsExactly(TrackedEventWarning.DETAILS_NOT_REPORTABLE)
    }

    @Test
    fun `keeps details with 20 top-level keys`() {
        val details = (1..20).associate { "k$it" to 1 }

        val admission = admittedWithDetails(details)

        assertThat(admission.entry.details).isEqualTo(details)
        assertThat(admission.warnings).isEmpty()
    }

    @Test
    fun `leaves out details with 21 top-level keys and keeps the event`() {
        val admission = admittedWithDetails((1..21).associate { "k$it" to 1 })

        assertThat(admission.entry.details).isNull()
        assertThat(admission.warnings).containsExactly(TrackedEventWarning.DETAILS_NOT_REPORTABLE)
    }

    @Test
    fun `keeps details of exactly 1 KB as JSON`() {
        val text = "a".repeat(1_016)

        val admission = admittedWithDetails(mapOf("k" to text))

        assertThat(admission.entry.toJsonText()).contains(""""details":{"k":"$text"},""")
        assertThat(admission.warnings).isEmpty()
    }

    @Test
    fun `leaves out details one byte over 1 KB as JSON and keeps the event`() {
        val admission = admittedWithDetails(mapOf("k" to "a".repeat(1_017)))

        assertThat(admission.entry.details).isNull()
        assertThat(admission.warnings).containsExactly(TrackedEventWarning.DETAILS_NOT_REPORTABLE)
    }

    @Test
    fun `measures non-ASCII text in bytes and writes it unescaped`() {
        val text = "é".repeat(508)

        val admission = admittedWithDetails(mapOf("k" to text))

        assertThat(admission.entry.toJsonText()).contains(""""details":{"k":"$text"},""")
    }

    @Test
    fun `leaves out details whose non-ASCII text goes over 1 KB in bytes`() {
        val admission = admittedWithDetails(mapOf("k" to "é".repeat(509)))

        assertThat(admission.entry.details).isNull()
    }

    @Test
    fun `measures slashes as one byte each and writes them unescaped`() {
        val text = "/".repeat(1_016)

        val admission = admittedWithDetails(mapOf("k" to text))

        assertThat(admission.entry.toJsonText()).contains(""""details":{"k":"$text"},""")
    }

    @Test
    fun `keeps details holding as many values as fit 1 KB`() {
        val values = List(508) { 0 }

        val admission = admittedWithDetails(mapOf("k" to values))

        assertThat(admission.entry.details).containsExactly("k", values)
    }

    @Test
    fun `is not altered when the caller changes the details afterwards`() {
        val items = mutableListOf("a")
        val details = linkedMapOf<String, Any?>("items" to items)
        val admission = admittedWithDetails(details)

        items += "b"
        details["plan"] = "pro"

        assertThat(admission.entry.details).containsExactly("items", listOf("a"))
    }
}
