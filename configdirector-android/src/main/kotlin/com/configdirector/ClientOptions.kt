package com.configdirector

/**
 * Settings for a [ConfigDirectorClient]. Read once when the client is built; changing them
 * afterwards has no effect.
 *
 * ```java
 * ClientOptions options = ClientOptions.builder()
 *     .metadata(new Metadata("Checkout", "4.2.0"))
 *     .connection(ConnectionOptions.builder().mode(ConnectionMode.POLLING).build())
 *     .build();
 * ```
 */
public class ClientOptions private constructor(
    /** Describes the calling application, so targeting rules can reference it. */
    public val metadata: Metadata,

    /** How the client connects to the ConfigDirector server. */
    public val connection: ConnectionOptions,

    /**
     * Whether, and where, the client keeps the last values it received on the device and serves
     * them on the next launch of the same context until live values arrive. On by default, in the
     * app's `SharedPreferences`; ignored in [ConnectionMode.OFFLINE].
     */
    public val caching: CachingOptions,

    /** Where the SDK writes its logs. Defaults to an [AndroidLogger] at [LogLevel.WARN]. */
    public val logger: ConfigDirectorLogger,
) {

    override fun toString(): String =
        "ClientOptions(metadata=$metadata, connection=$connection, caching=$caching, logger=$logger)"

    /** Collects client settings. Every setter returns this, so calls chain. */
    public class Builder internal constructor() {
        private var metadata: Metadata = Metadata.empty()
        private var connection: ConnectionOptions = ConnectionOptions.defaults()
        private var caching: CachingOptions = CachingOptions.defaults()
        private var logger: ConfigDirectorLogger = AndroidLogger()

        /** Describes the calling application. */
        public fun metadata(metadata: Metadata): Builder = apply { this.metadata = metadata }

        /** Shorthand for [metadata] with both fields. */
        public fun metadata(appName: String?, appVersion: String?): Builder =
            metadata(Metadata(appName, appVersion))

        /** Adjusts how the client connects. */
        public fun connection(connection: ConnectionOptions): Builder =
            apply { this.connection = connection }

        /** Adjusts how the client connects. */
        @JvmSynthetic
        public fun connection(configure: ConnectionOptions.Builder.() -> Unit): Builder =
            connection(ConnectionOptions.build(configure))

        /** Adjusts whether, and where, the client keeps the last values it received on the device. */
        public fun caching(caching: CachingOptions): Builder = apply { this.caching = caching }

        /** Adjusts whether, and where, the client keeps the last values it received on the device. */
        @JvmSynthetic
        public fun caching(configure: CachingOptions.Builder.() -> Unit): Builder =
            caching(CachingOptions.build(configure))

        /** Where the SDK writes its logs. */
        public fun logger(logger: ConfigDirectorLogger): Builder = apply { this.logger = logger }

        /** Builds the settings. */
        public fun build(): ClientOptions =
            ClientOptions(metadata = metadata, connection = connection, caching = caching, logger = logger)
    }

    public companion object {
        private val DEFAULTS: ClientOptions = Builder().build()

        /** Starts from the defaults. */
        @JvmStatic
        public fun builder(): Builder = Builder()

        /** The settings a client uses when none are supplied. */
        @JvmStatic
        public fun defaults(): ClientOptions = DEFAULTS

        /**
         * Builds client settings.
         *
         * ```kotlin
         * val options = ClientOptions.build {
         *     metadata("Checkout", "4.2.0")
         *     connection { mode(ConnectionMode.POLLING) }
         * }
         * ```
         */
        @JvmSynthetic
        public fun build(configure: Builder.() -> Unit): ClientOptions =
            Builder().apply(configure).build()
    }
}
