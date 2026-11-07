package com.configdirector.testing

import com.configdirector.ConfigDirectorContext

/** An event the code under test tracked, as a test client recorded it. */
public class TrackedEvent @JvmOverloads constructor(
    /** The name the event was tracked with. */
    public val name: String,

    /** The context the event was tracked for, or null when the client had none. */
    public val context: ConfigDirectorContext? = null,

    /**
     * The value the event was tracked with, or null when there was none or it was not a finite
     * number. A `Float` is recorded as the `Double` with the same digits.
     */
    public val value: Number? = null,

    /**
     * The details the event was tracked with, or null when there were none or they were outside the
     * limits. A date is recorded as the ISO 8601 text the SDK sends.
     */
    public val details: Map<String, Any?>? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is TrackedEvent &&
                    name == other.name &&
                    context == other.context &&
                    value == other.value &&
                    details == other.details
                )

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + context.hashCode()
        result = 31 * result + value.hashCode()
        result = 31 * result + details.hashCode()
        return result
    }

    override fun toString(): String = "TrackedEvent(name=$name, context=$context, value=$value, details=$details)"
}
