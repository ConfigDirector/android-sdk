package com.configdirector.internal.transport

import com.configdirector.ConfigDirectorContext
import com.configdirector.ConfigDirectorLogger
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/** Metadata about the SDK and the app it runs in, sent with every request. */
internal class SdkMetaContext(
    val sdkName: String,
    val sdkVersion: String,
    val appName: String?,
    val appVersion: String?,
    val userAgent: String?,
)

/** Everything a [Transport] needs to reach the ConfigDirector server. */
internal class TransportOptions(
    val clientSdkKey: String,
    val baseUrl: String,
    val metaContext: SdkMetaContext,
    val instanceId: String,
    val logger: ConfigDirectorLogger,
    val pollingIntervalMillis: Long,
    val httpClient: OkHttpClient,
    /** How long to wait before the reconnection attempt numbered [attempt], counting from 1. */
    val retryDelayMillis: (attempt: Int) -> Long = ::exponentialRetryDelayMillis,
) {
    fun endpoint(path: String): HttpUrl =
        baseUrl.trimEnd('/').plus("/").plus(path).toHttpUrl()

    fun payload(context: ConfigDirectorContext, lastUpdateTimestamp: String? = null): String {
        val payload = JSONObject()
            .put("givenContext", contextJson(context))
            .put("metaContext", metaContextJson())
            .put("clientSdkKey", clientSdkKey)
            .put("instanceId", instanceId)

        lastUpdateTimestamp?.let { payload.put("lastUpdateTimestamp", it) }

        return payload.toString()
    }

    private fun contextJson(context: ConfigDirectorContext): JSONObject {
        val json = JSONObject()
        context.id?.let { json.put("id", it) }
        context.name?.let { json.put("name", it) }
        context.traits?.let { json.put("traits", jsonValue(it)) }
        json.put("anonymous", context.isAnonymous)
        return json
    }

    private fun metaContextJson(): JSONObject {
        val json = JSONObject()
            .put("sdkName", metaContext.sdkName)
            .put("sdkVersion", metaContext.sdkVersion)
        metaContext.appName?.let { json.put("appName", it) }
        metaContext.appVersion?.let { json.put("appVersion", it) }
        metaContext.userAgent?.let { json.put("userAgent", it) }
        return json
    }

    // Traits are JSON-shaped by the time they get here: ConfigDirectorContext.Builder rejects
    // anything else when it builds.
    private fun jsonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().apply {
            value.forEach { (key, element) -> put(key.toString(), jsonValue(element)) }
        }
        is List<*> -> JSONArray().apply {
            value.forEach { element -> put(jsonValue(element)) }
        }
        else -> value
    }
}

/** 2^9 seconds is a little over 8 minutes, which caps the backoff to under 10. */
private const val MAX_EXPONENTIAL_ATTEMPT = 9

internal fun exponentialRetryDelayMillis(attempt: Int): Long =
    1_000L shl attempt.coerceIn(0, MAX_EXPONENTIAL_ATTEMPT)
