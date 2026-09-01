package com.configdirector.internal

import com.configdirector.ConfigDirectorContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Reads a JSON document into plain JDK collections, so that no JSON type appears in the SDK's public
 * API. Values inside are String, Number, Boolean, List, Map, or null.
 */
internal fun String.jsonObjectOrNull(): Map<String, Any?>? = try {
    JSONObject(this).toValueMap()
} catch (malformed: JSONException) {
    null
}

/** See [jsonObjectOrNull]. */
internal fun String.jsonArrayOrNull(): List<Any?>? = try {
    JSONArray(this).toValueList()
} catch (malformed: JSONException) {
    null
}

/** The context as the ConfigDirector API expects it, on every request that carries one. */
internal fun ConfigDirectorContext.toJson(): JSONObject = JSONObject().also { json ->
    id?.let { json.put("id", it) }
    name?.let { json.put("name", it) }
    // Traits are JSON-shaped by the time they get here: the context builder rejects anything else
    // when it builds.
    traits?.let { json.put("traits", it.toJsonValue()) }
    json.put("anonymous", isAnonymous)
}

/** The JSON text for a value made of maps, lists, strings, numbers and booleans. */
internal fun Any?.toJsonText(): String = when (this) {
    is Map<*, *>, is List<*> -> toJsonValue().toString()
    else -> toString()
}

private fun Any?.toJsonValue(): Any = when (this) {
    null -> JSONObject.NULL
    is Map<*, *> -> JSONObject().also { json ->
        forEach { (key, element) -> json.put(key.toString(), element.toJsonValue()) }
    }
    is List<*> -> JSONArray().also { json -> forEach { element -> json.put(element.toJsonValue()) } }
    else -> this
}

private fun JSONObject.toValueMap(): Map<String, Any?> =
    keys().asSequence().associateWith { key -> get(key).toValue() }

private fun JSONArray.toValueList(): List<Any?> = (0 until length()).map { index -> get(index).toValue() }

private fun Any?.toValue(): Any? = when (this) {
    JSONObject.NULL, null -> null
    is JSONObject -> toValueMap()
    is JSONArray -> toValueList()
    else -> this
}
