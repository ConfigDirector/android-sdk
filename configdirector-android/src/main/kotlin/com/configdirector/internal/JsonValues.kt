package com.configdirector.internal

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
