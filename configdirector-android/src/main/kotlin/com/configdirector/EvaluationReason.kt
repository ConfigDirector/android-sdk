package com.configdirector

/** Why a config evaluated to the value it did. */
public enum class EvaluationReason(
    /** How ConfigDirector spells this reason on the wire, such as `found-match`. */
    public val wireName: String,
) {
    /** The config state was found and its value matched the requested type. */
    FOUND_MATCH("found-match"),

    /**
     * A server SDK skipped a percentage rollout because the context has no identifier to place it
     * in a share, and returned the default targeting rule value. This SDK never returns it: a
     * context without an identifier gets one derived from an ID the SDK stores for the app install.
     */
    ROLLOUT_SKIPPED_NO_IDENTIFIER("rollout-skipped-no-identifier"),

    /** No state was received for the config key. */
    CONFIG_STATE_MISSING("config-state-missing"),

    /** The client had not received config state yet. */
    CLIENT_NOT_READY("client-not-ready"),

    /** The config's type is incompatible with the requested type. */
    TYPE_MISMATCH("type-mismatch"),

    /** The config has no value for the current context. */
    VALUE_MISSING("value-missing"),

    /** The config value could not be read as a number. */
    INVALID_NUMBER("invalid-number"),

    /** The config value could not be read as JSON. */
    INVALID_JSON("invalid-json"),

    /** The config value could not be read as a boolean. */
    INVALID_BOOLEAN("invalid-boolean"),

    /**
     * The value was served from the values kept on the device from the last payload, before live
     * values arrived. It is not an in-code default value.
     */
    CACHED("cached"),
}
