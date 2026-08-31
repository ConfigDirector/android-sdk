package com.configdirector.internal

/** Why a config evaluated to the value it did. */
internal enum class EvaluationReason(val wireName: String) {
    /** The config state was found and its value matched the requested type. */
    FOUND_MATCH("found-match"),

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
}
