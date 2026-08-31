package com.configdirector

import java.net.URI
import java.net.URISyntaxException

/**
 * How the client connects to the ConfigDirector server.
 *
 * Settings are checked when they are built, so an unusable one is reported where it was written
 * rather than as a client that quietly never updates.
 */
public class ConnectionOptions private constructor(
    /** How the client keeps its config state current. */
    public val mode: ConnectionMode,

    /** How long the client waits between polls, used only in [ConnectionMode.POLLING]. */
    public val pollingIntervalMillis: Long,

    /**
     * How long initialization and context updates wait for config state.
     *
     * When streaming, the operation may still succeed after it elapses as long as nothing
     * unrecoverable happened. In the other modes a timed-out operation is not retried.
     */
    public val timeoutMillis: Long,

    /** The base URL of the ConfigDirector SDK server, set only when routing through a proxy. */
    public val baseUrl: String?,

    /**
     * Whether to pause the connection while the app is in the background and resume it when the app
     * returns to the foreground.
     *
     * Android stops background connections on its own, so this is on by default. Set it to false to
     * manage the connection yourself with `ConfigDirectorClient.pauseNetwork()` and
     * `ConfigDirectorClient.resumeNetwork()`.
     */
    public val pausesWhileBackgrounded: Boolean,
) {

    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is ConnectionOptions &&
                    mode == other.mode &&
                    pollingIntervalMillis == other.pollingIntervalMillis &&
                    timeoutMillis == other.timeoutMillis &&
                    baseUrl == other.baseUrl &&
                    pausesWhileBackgrounded == other.pausesWhileBackgrounded
                )

    override fun hashCode(): Int {
        var result = mode.hashCode()
        result = 31 * result + pollingIntervalMillis.hashCode()
        result = 31 * result + timeoutMillis.hashCode()
        result = 31 * result + baseUrl.hashCode()
        result = 31 * result + pausesWhileBackgrounded.hashCode()
        return result
    }

    override fun toString(): String =
        "ConnectionOptions(mode=$mode, pollingIntervalMillis=$pollingIntervalMillis, " +
            "timeoutMillis=$timeoutMillis, baseUrl=$baseUrl, " +
            "pausesWhileBackgrounded=$pausesWhileBackgrounded)"

    /** Collects connection settings. Every setter returns this, so calls chain. */
    public class Builder internal constructor() {
        private var mode: ConnectionMode = ConnectionMode.STREAMING
        private var pollingIntervalMillis: Long = DEFAULT_POLLING_INTERVAL_MILLIS
        private var timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
        private var baseUrl: String? = null
        private var pausesWhileBackgrounded: Boolean = true

        /** How the client keeps its config state current. Defaults to [ConnectionMode.STREAMING]. */
        public fun mode(mode: ConnectionMode): Builder = apply { this.mode = mode }

        /**
         * How long to wait between polls, in milliseconds. Used only in [ConnectionMode.POLLING];
         * defaults to 60 seconds. Must be positive: [ConnectionMode.ONE_TIME] is how to ask for a
         * single fetch instead.
         */
        public fun pollingIntervalMillis(pollingIntervalMillis: Long): Builder =
            apply { this.pollingIntervalMillis = pollingIntervalMillis }

        /**
         * How long initialization and context updates wait for config state, in milliseconds.
         * Defaults to 3 seconds. Must be positive.
         */
        public fun timeoutMillis(timeoutMillis: Long): Builder =
            apply { this.timeoutMillis = timeoutMillis }

        /** The base URL to connect to. Only needed when routing through a proxy. */
        public fun baseUrl(baseUrl: String?): Builder = apply { this.baseUrl = baseUrl }

        /**
         * Whether the client pauses its connection while the app is backgrounded. Defaults to true.
         */
        public fun pausesWhileBackgrounded(pausesWhileBackgrounded: Boolean): Builder =
            apply { this.pausesWhileBackgrounded = pausesWhileBackgrounded }

        /**
         * Builds the settings.
         *
         * @throws ConfigDirectorValidationException if a duration is not positive or is longer than
         *   can be waited on, or if the base URL is not absolute or names no host.
         */
        public fun build(): ConnectionOptions {
            requirePositive(pollingIntervalMillis, "pollingIntervalMillis")
            requirePositive(timeoutMillis, "timeoutMillis")
            requireAtMostMaxInt(timeoutMillis, "timeoutMillis")
            requireUsableUrl(baseUrl)
            return ConnectionOptions(
                mode = mode,
                pollingIntervalMillis = pollingIntervalMillis,
                timeoutMillis = timeoutMillis,
                baseUrl = baseUrl,
                pausesWhileBackgrounded = pausesWhileBackgrounded,
            )
        }
    }

    public companion object {
        private const val DEFAULT_POLLING_INTERVAL_MILLIS = 60_000L
        private const val DEFAULT_TIMEOUT_MILLIS = 3_000L
        private val DEFAULTS: ConnectionOptions = Builder().build()

        /** Starts from the defaults. */
        @JvmStatic
        public fun builder(): Builder = Builder()

        /** Streaming, a 60 second polling interval, and a 3 second timeout. */
        @JvmStatic
        public fun defaults(): ConnectionOptions = DEFAULTS

        /**
         * Builds connection settings.
         *
         * ```kotlin
         * val connection = ConnectionOptions.build { mode(ConnectionMode.POLLING) }
         * ```
         */
        @JvmSynthetic
        public fun build(configure: Builder.() -> Unit): ConnectionOptions =
            Builder().apply(configure).build()
    }
}

private fun requirePositive(value: Long, name: String) {
    if (value <= 0) {
        throw ConfigDirectorValidationException(
            "Invalid $name '$value'. It must be a positive number of milliseconds.",
        )
    }
}

// The longest timeout OkHttp accepts, which is what the timeout is ultimately handed to. Past this
// it rejects the request outright, and initialization reports a client that never becomes ready
// rather than one that waited too long.
private fun requireAtMostMaxInt(value: Long, name: String) {
    if (value > Int.MAX_VALUE) {
        throw ConfigDirectorValidationException(
            "Invalid $name '$value'. It must be no longer than ${Int.MAX_VALUE}ms, which is the " +
                "longest the HTTP client accepts.",
        )
    }
}

private fun requireUsableUrl(baseUrl: String?) {
    if (baseUrl.isNullOrBlank()) return

    val parsed = try {
        URI(baseUrl.trim())
    } catch (malformed: URISyntaxException) {
        throw ConfigDirectorValidationException(
            "Invalid base URL '$baseUrl'. ${malformed.message}",
        )
    }

    if (!parsed.isAbsolute || parsed.host == null) {
        throw ConfigDirectorValidationException(
            "Invalid base URL '$baseUrl'. It must be absolute and name a host.",
        )
    }
}
