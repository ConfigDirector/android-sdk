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
internal class ConfigState(
    val key: String,
    val type: ConfigType,
    /** The evaluated value, serialized as a string, or null when the config has no value. */
    val value: String?,
    val id: String = "",
    /** An opaque identifier of the evaluated value, used for telemetry. */
    val valueId: String? = null,
)

/** A batch of config state received from the ConfigDirector server. */
internal class ConfigSet(val configs: Map<String, ConfigState>)
