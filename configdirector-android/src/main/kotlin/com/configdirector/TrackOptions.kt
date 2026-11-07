package com.configdirector

/**
 * What a tracked event carries besides its name: a number it measures and details about it.
 *
 * ```kotlin
 * client.track("checkout-completed", TrackOptions(value = 49.9, details = mapOf("plan" to "pro")))
 * ```
 *
 * ```java
 * client.track("checkout-completed", new TrackOptions(49.9, Map.of("plan", "pro")));
 * ```
 */
public class TrackOptions @JvmOverloads constructor(
    /**
     * A number the event measures, such as an amount of money, a count or a duration. ConfigDirector
     * stores it as an exact decimal, and an `Int`, a `Long`, a `BigInteger` or a `BigDecimal` is sent
     * with every digit. A value that is not a finite number is left out and the event is still
     * reported. ConfigDirector stores an event without its value when the value is written in more
     * than 64 characters or has a decimal exponent beyond 400.
     */
    public val value: Number? = null,

    /**
     * Details of the event, such as the plan a customer chose. Values are `String`, `Number`,
     * `Boolean`, `Map` with `String` keys, `Collection`, or null, and a number that is not finite is
     * sent as null. A `java.util.Date`, `Instant`, `OffsetDateTime` or `ZonedDateTime`, at any depth,
     * is sent as ISO 8601 text in UTC, such as `2026-12-01T00:00:00.000Z`. Details with more than 20
     * top-level keys, larger than 1 KB as JSON, or holding any other type of value, such as a
     * `LocalDate`, are left out and the event is still reported.
     */
    public val details: Map<String, Any?>? = null,
)
