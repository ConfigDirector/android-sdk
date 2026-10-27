package com.configdirector.compose

import java.io.Closeable
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONException
import org.json.JSONObject

/**
 * Stands in for the ConfigDirector SDK server, serving `dark-mode` as the plan the context carries.
 * The bindings are exercised through a real client so that nothing between them and the server is
 * stubbed.
 */
class FakeSdkServer : Closeable {

    private val server = MockWebServer()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = respondTo(request)
        }
        server.start()
    }

    val baseUrl: String get() = server.url("/").toString()

    override fun close() {
        server.shutdown()
    }

    private fun respondTo(request: RecordedRequest): MockResponse {
        val response = MockResponse()

        val payload = payloadOrNull(request)
            ?: return response.setResponseCode(BAD_REQUEST).setBody("the server could not parse the request body")

        if (request.path?.contains("telemetry") == true) return response

        val isPro = payload
            .optJSONObject("givenContext")
            ?.optJSONObject("traits")
            ?.optString("plan") == "pro"

        val body = JSONObject()
            .put("kind", "full")
            .put(
                "configs",
                JSONObject().put(
                    "dark-mode",
                    JSONObject()
                        .put("id", "c1")
                        .put("key", "dark-mode")
                        .put("type", "boolean")
                        .put("value", isPro.toString())
                        .put("valueId", "dark-mode-${if (isPro) "pro" else "free"}"),
                ),
            )
            .toString()

        return if (request.path?.contains("sse") == true) {
            response.setHeader("Content-Type", "text/event-stream").setBody("data: $body\n\n")
        } else {
            response.setHeader("Content-Type", "application/json").setBody(body)
        }
    }

    private fun payloadOrNull(request: RecordedRequest): JSONObject? =
        try {
            JSONObject(request.body.peek().readUtf8())
        } catch (malformed: JSONException) {
            null
        }

    private companion object {
        const val BAD_REQUEST = 400
    }
}
