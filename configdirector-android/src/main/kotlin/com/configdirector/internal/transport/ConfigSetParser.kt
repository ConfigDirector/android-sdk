package com.configdirector.internal.transport

import com.configdirector.internal.ConfigSet
import com.configdirector.internal.ConfigSetKind
import com.configdirector.internal.ConfigState
import com.configdirector.internal.ConfigType
import org.json.JSONException
import org.json.JSONObject

/**
 * Reads the config state the server sends.
 *
 * Every field is optional: a config set carrying a key the SDK does not know about, or missing one
 * it does, is a server that has moved on rather than an error to fail the connection over.
 */
internal object ConfigSetParser {

    @Throws(JSONException::class)
    fun parse(body: String): ConfigSet {
        val json = JSONObject(body)
        val configsJson = json.optJSONObject("configs")
        val configs = LinkedHashMap<String, ConfigState>()

        configsJson?.keys()?.forEach { key ->
            configsJson.optJSONObject(key)?.let { configs[key] = configState(key, it) }
        }

        return ConfigSet(
            configs = configs,
            kind = if (json.optString("kind") == "delta") ConfigSetKind.DELTA else ConfigSetKind.FULL,
            timestamp = json.optStringOrNull("timestamp"),
        )
    }

    private fun configState(key: String, json: JSONObject) = ConfigState(
        key = json.optStringOrNull("key") ?: key,
        type = ConfigType.of(json.optString("type")),
        value = json.optStringOrNull("value"),
        id = json.optString("id"),
        valueId = json.optStringOrNull("valueId"),
    )

    // optString hands back "" for a missing field and for an explicit JSON null alike, and a config
    // with no value is meaningfully different from one holding an empty string.
    private fun JSONObject.optStringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name).ifEmpty { null }
}
