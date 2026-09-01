package com.configdirector.internal

import com.configdirector.ConfigDirectorContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Reads a JSON document into plain JDK collections, so that no JSON type appears in the SDK's public
 * API. Values inside are String, Number, Boolean, List, Map, or null.
 */
internal object JsonValues {

    fun objectOrNull(rawValue: String): Map<String, Any?>? = try {
        toMap(JSONObject(rawValue))
    } catch (malformed: JSONException) {
        null
    }

    fun arrayOrNull(rawValue: String): List<Any?>? = try {
        toList(JSONArray(rawValue))
    } catch (malformed: JSONException) {
        null
    }

    /** The context as the ConfigDirector API expects it, on every request that carries one. */
    fun contextJson(context: ConfigDirectorContext): JSONObject = JSONObject().apply {
        context.id?.let { put("id", it) }
        context.name?.let { put("name", it) }
        // Traits are JSON-shaped by the time they get here: the context builder rejects anything
        // else when it builds.
        context.traits?.let { put("traits", toJson(it)) }
        put("anonymous", context.isAnonymous)
    }

    /** The JSON text for a value made of maps, lists, strings, numbers and booleans. */
    fun toJsonText(value: Any?): String = when (value) {
        is Map<*, *>, is List<*> -> toJson(value).toString()
        else -> value.toString()
    }

    private fun toJson(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().apply {
            value.forEach { (key, element) -> put(key.toString(), toJson(element)) }
        }
        is List<*> -> JSONArray().apply { value.forEach { element -> put(toJson(element)) } }
        else -> value
    }

    private fun toMap(json: JSONObject): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>(json.length())
        json.keys().forEach { key -> map[key] = toValue(json.get(key)) }
        return map
    }

    private fun toList(json: JSONArray): List<Any?> =
        (0 until json.length()).map { index -> toValue(json.get(index)) }

    private fun toValue(value: Any?): Any? = when (value) {
        JSONObject.NULL, null -> null
        is JSONObject -> toMap(value)
        is JSONArray -> toList(value)
        else -> value
    }
}
