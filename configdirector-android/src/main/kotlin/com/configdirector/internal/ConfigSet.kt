package com.configdirector.internal

/** The type a config was declared with in the ConfigDirector dashboard. */
internal enum class ConfigType(val wireName: String) {
    CUSTOM("custom"),
    BOOLEAN("boolean"),
    STRING("string"),
    INTEGER("integer"),
    FLOAT("float"),
    ENUMERATION("enum"),
    URL("url"),
    JSON("json"),
    ;

    companion object {
        fun of(wireName: String): ConfigType =
            entries.firstOrNull { it.wireName == wireName } ?: CUSTOM
    }
}

/** The evaluated state of a single config, as returned by the server. */
internal data class ConfigState(
    val key: String,
    val type: ConfigType,
    /** The evaluated value, serialized as a string, or null when the config has no value. */
    val value: String?,
    val id: String = "",
    /** An opaque identifier of the evaluated value, used for telemetry. */
    val valueId: String? = null,
) {
    /**
     * The value read as a JSON document, parsed on the first read that asks for it.
     *
     * A config is read far more often than it changes -- once per recomposition, for instance --
     * and parsing it every time would put that work on the caller's thread.
     */
    val jsonObject: Map<String, Any?>? by lazy { value?.jsonObjectOrNull() }

    /** See [jsonObject]. */
    val jsonArray: List<Any?>? by lazy { value?.jsonArrayOrNull() }
}

/**
 * Whether a [ConfigSet] carries the complete config state or only the configs that changed since
 * the last one.
 */
internal enum class ConfigSetKind { FULL, DELTA }

/** A batch of config state received from the ConfigDirector server. */
internal data class ConfigSet(
    val configs: Map<String, ConfigState>,
    val kind: ConfigSetKind = ConfigSetKind.FULL,
    /**
     * The server timestamp of this set, echoed back on the next polling request so the server can
     * answer with a delta.
     */
    val timestamp: String? = null,
)
