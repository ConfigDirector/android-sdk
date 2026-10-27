package com.configdirector.internal

import com.configdirector.ConfigDirectorContext
import com.configdirector.internal.transport.optStringOrNull
import com.configdirector.internal.transport.toConfigState
import org.json.JSONException
import org.json.JSONObject

private const val ENTRY_FORMAT_VERSION = 2
private const val FIRST_ENTRY_FORMAT_VERSION = 1
private const val DEFAULT_CONTEXT_KIND = "user"

internal sealed class ContextTag {
    data class Identified(val contextKind: String, val contextId: String) : ContextTag()

    data class Anonymous(val contextKind: String, val instanceId: String) : ContextTag()
}

internal fun contextTagFor(context: ConfigDirectorContext?, instanceId: String): ContextTag {
    val contextKind = context?.kind ?: DEFAULT_CONTEXT_KIND
    val contextId = context?.id ?: return ContextTag.Anonymous(contextKind, instanceId)
    return ContextTag.Identified(contextKind, contextId)
}

internal data class CachedValues(
    val contextTag: ContextTag,
    val environmentId: String,
    val projectId: String,
    val configs: Map<String, ConfigState>,
    val receivedAt: Long,
)

internal enum class CachedValuesAdoption { ADOPT, LIVE_VALUES_ALREADY_ARRIVED, CONTEXT_MISMATCH, CLIENT_CLOSED }

internal fun decideCachedValuesAdoption(
    cachedValues: CachedValues,
    launchContextTag: ContextTag,
    hasReceivedLiveValues: Boolean,
    isClosed: Boolean,
): CachedValuesAdoption = when {
    isClosed -> CachedValuesAdoption.CLIENT_CLOSED
    hasReceivedLiveValues -> CachedValuesAdoption.LIVE_VALUES_ALREADY_ARRIVED
    cachedValues.contextTag != launchContextTag -> CachedValuesAdoption.CONTEXT_MISMATCH
    else -> CachedValuesAdoption.ADOPT
}

internal fun serializeCachedValuesEntry(clientSdkKeyDigest: String, cachedValues: CachedValues): String =
    JSONObject()
        .put("formatVersion", ENTRY_FORMAT_VERSION)
        .put("clientSdkKeyDigest", clientSdkKeyDigest)
        .put("contextTag", cachedValues.contextTag.toJson())
        .put("environmentId", cachedValues.environmentId)
        .put("projectId", cachedValues.projectId)
        .put("configs", JSONObject().apply { cachedValues.configs.forEach { (key, state) -> put(key, state.toJson()) } })
        .put("receivedAt", cachedValues.receivedAt)
        .toString()

internal fun parseCachedValuesEntry(
    serialized: String,
    clientSdkKeyDigest: String,
): CachedValuesRead.EntryParseOutcome {
    val entry = try {
        JSONObject(serialized)
    } catch (malformed: JSONException) {
        return CachedValuesRead.NotAnEntry
    }
    return when (entry.optInt("formatVersion")) {
        ENTRY_FORMAT_VERSION -> entry.cachedValuesFor(clientSdkKeyDigest)
            ?.let { CachedValuesRead.Stored(it) }
            ?: CachedValuesRead.NotAnEntry
        FIRST_ENTRY_FORMAT_VERSION -> CachedValuesRead.FirstFormatEntry
        else -> CachedValuesRead.NotAnEntry
    }
}

private fun JSONObject.cachedValuesFor(clientSdkKeyDigest: String): CachedValues? {
    if (optString("clientSdkKeyDigest") != clientSdkKeyDigest) return null

    val contextTag = optJSONObject("contextTag")?.toContextTag() ?: return null
    val configsJson = optJSONObject("configs") ?: return null
    if (!has("receivedAt")) return null

    return CachedValues(
        contextTag = contextTag,
        environmentId = optString("environmentId"),
        projectId = optString("projectId"),
        configs = buildMap {
            configsJson.keys().forEach { key -> configsJson.optJSONObject(key)?.let { put(key, it.toConfigState(key)) } }
        },
        receivedAt = optLong("receivedAt"),
    )
}

private fun ContextTag.toJson(): JSONObject = when (this) {
    is ContextTag.Identified -> JSONObject().put("contextKind", contextKind).put("contextId", contextId)
    is ContextTag.Anonymous -> JSONObject().put("contextKind", contextKind).put("anonymousInstanceId", instanceId)
}

private fun JSONObject.toContextTag(): ContextTag? {
    val contextKind = optStringOrNull("contextKind") ?: return null
    optStringOrNull("contextId")?.let { return ContextTag.Identified(contextKind, it) }
    optStringOrNull("anonymousInstanceId")?.let { return ContextTag.Anonymous(contextKind, it) }
    return null
}

private fun ConfigState.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("key", key)
    .put("type", type.wireName)
    .put("value", value ?: JSONObject.NULL)
    .put("valueId", valueId ?: JSONObject.NULL)
