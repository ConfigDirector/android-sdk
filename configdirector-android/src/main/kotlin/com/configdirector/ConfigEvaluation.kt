package com.configdirector

/** The outcome of evaluating a single config. */
public class ConfigEvaluation internal constructor(
    /** The key of the config that was evaluated. */
    public val key: String,

    /**
     * The value the config evaluated to, which is the default value supplied by the caller when
     * [isDefaultValue] is true. It is a `Boolean`, `String`, `Integer` or `Double`, matching the
     * accessor the config was read with.
     */
    public val value: Any,

    /**
     * Identifies the specific config value that was served, for telemetry. It is null when the
     * default value was returned.
     */
    public val valueId: String?,

    /** Whether the default value provided by the caller was returned. */
    public val isDefaultValue: Boolean,

    /** Why the config evaluated to [value]. */
    public val reason: EvaluationReason,

    /** The context the config was evaluated against, if one was set. */
    public val context: ConfigDirectorContext?,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is ConfigEvaluation &&
                    key == other.key &&
                    value == other.value &&
                    valueId == other.valueId &&
                    isDefaultValue == other.isDefaultValue &&
                    reason == other.reason &&
                    context == other.context
                )

    override fun hashCode(): Int {
        var result = key.hashCode()
        result = 31 * result + value.hashCode()
        result = 31 * result + valueId.hashCode()
        result = 31 * result + isDefaultValue.hashCode()
        result = 31 * result + reason.hashCode()
        result = 31 * result + context.hashCode()
        return result
    }

    override fun toString(): String =
        "ConfigEvaluation(key=$key, value=$value, valueId=$valueId, " +
            "isDefaultValue=$isDefaultValue, reason=$reason, context=$context)"
}
