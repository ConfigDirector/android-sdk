package com.configdirector

import java.io.Closeable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject

/**
 * Stands in for the ConfigDirector SDK server. It evaluates the context each request carries the way
 * targeting rules would, and answers on whichever endpoint the transport under test uses, so a test
 * exercises the client through its public API with nothing inside the SDK stubbed.
 */
class FakeSdkServer : Closeable {

    private val server = MockWebServer()
    private val scripted = ConcurrentLinkedQueue<String>()

    /** The status to answer with, so a test can see what the client does with a failure. */
    @Volatile
    var status: Int = 200

    /** How long the server sits on a request before answering it. */
    @Volatile
    var responseDelayMillis: Long = 0

    /** Whether the server has config state to send, or answers the connection with nothing. */
    @Volatile
    var sendsConfigState: Boolean = true

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = respondTo(request)
        }
        server.start()
    }

    val baseUrl: String get() = server.url("/").toString()

    val requestCount: Int get() = server.requestCount

    /** The next request the server received, or null if none arrived within [timeoutMillis]. An
     * unbounded wait would hang the whole suite when the client never connects. */
    fun takeRequest(timeoutMillis: Long = 5_000): RecordedRequest? =
        server.takeRequest(timeoutMillis, TimeUnit.MILLISECONDS)

    /** Answers the next request with [body] instead of evaluating the context it carries. */
    fun script(body: String) {
        scripted += body
    }

    /** A config set carrying only what changed, which the client merges into what it already has. */
    fun scriptDelta(key: String, type: String, value: String) {
        script(
            JSONObject()
                .put("kind", "delta")
                .put("configs", JSONObject().put(key, configJson(key, type, value, "$key-delta")))
                .toString(),
        )
    }

    override fun close() {
        server.shutdown()
    }

    private fun respondTo(request: RecordedRequest): MockResponse {
        val response = MockResponse().setHeadersDelay(responseDelayMillis, TimeUnit.MILLISECONDS)
        if (status != 200) {
            return response.setResponseCode(status).setBody("the server rejected the request")
        }

        val isStream = request.path?.contains("sse") == true
        if (!sendsConfigState) {
            return if (isStream) {
                response.setHeader("Content-Type", "text/event-stream").setBody("")
            } else {
                response.setResponseCode(NO_CONTENT)
            }
        }

        val body = scripted.poll() ?: evaluate(request.body.peek().readUtf8())

        return if (isStream) {
            response.setHeader("Content-Type", "text/event-stream").setBody("data: $body\n\n")
        } else {
            response.setHeader("Content-Type", "application/json").setBody(body)
        }
    }

    private fun evaluate(payload: String): String {
        val given = JSONObject(payload).optJSONObject("givenContext") ?: JSONObject()
        val isPro = given.optJSONObject("traits")?.optString("plan") == "pro"
        val variant = if (isPro) "pro" else "free"
        val name = given.optString("name").ifEmpty { "there" }

        val configs = JSONObject()
            .put("dark-mode", configJson("dark-mode", "boolean", isPro.toString(), "dark-mode-$variant"))
            .put(
                "welcome-message",
                configJson("welcome-message", "string", "Hello, $name", "welcome-message-$variant"),
            )
            .put(
                "max-items",
                configJson("max-items", "integer", if (isPro) "25" else "10", "max-items-$variant"),
            )
            .put("sample-rate", configJson("sample-rate", "float", "0.25", "sample-rate-only"))
            .put("theme", configJson("theme", "json", THEME_DOCUMENT, "theme-only"))
            .put(
                "feature-list",
                configJson("feature-list", "json", """["alpha","beta"]""", "feature-list-only"),
            )
            .put("broken-json", configJson("broken-json", "json", "{oops", "broken-json-only"))
            .put("beta-banner", configJson("beta-banner", "boolean", null, null))

        return JSONObject()
            .put("environmentId", "env-1")
            .put("projectId", "project-1")
            .put("kind", "full")
            .put("timestamp", TIMESTAMP)
            .put("configs", configs)
            .toString()
    }

    private fun configJson(key: String, type: String, value: String?, valueId: String?) = JSONObject()
        .put("id", "$key-id")
        .put("key", key)
        .put("type", type)
        .put("value", value ?: JSONObject.NULL)
        .put("valueId", valueId ?: JSONObject.NULL)

    companion object {
        private const val NO_CONTENT = 204

        const val THEME_DOCUMENT: String =
            """{"primary":"#101010","spacing":{"small":4},"tags":["a",null],"enabled":true}"""
        const val TIMESTAMP: String = "2026-08-31T00:00:00Z"
    }
}
