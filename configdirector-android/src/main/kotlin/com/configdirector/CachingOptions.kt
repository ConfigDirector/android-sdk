package com.configdirector

/**
 * A store for the values the client keeps on the device between launches.
 *
 * The client keeps the last values it received from ConfigDirector as one string whose format
 * belongs to the SDK, and reads it back on the next launch. Implement this to keep that string
 * somewhere other than the app's `SharedPreferences`, for example in an encrypted store, or to
 * allow a larger entry than the default store does. One instance serves one client.
 *
 * A provider never stops the client: a [read] that throws is logged once and the client starts
 * without kept values, and a [write] or [remove] that throws is logged once and leaves the entry
 * that was there. The operations are `suspend` functions, so a provider is implemented from Kotlin.
 */
public interface CachingProvider {
    /** The stored entry, or null when nothing is stored. */
    public suspend fun read(): String?

    /** Replaces the stored entry with [value]. */
    public suspend fun write(value: String)

    /** Removes the stored entry, if any. */
    public suspend fun remove()

    /**
     * The largest entry, in bytes, the store accepts. The client measures every entry against it
     * before writing, and removes the entry instead when it is larger.
     */
    public fun sizeLimit(): Int
}

/**
 * Whether, and where, the client keeps the last values it received on the device, to serve them on
 * the next launch of the same context until live values arrive. See [ClientOptions.caching].
 *
 * ```java
 * CachingOptions caching = CachingOptions.builder().enabled(false).build();
 * ```
 */
public class CachingOptions private constructor(
    /**
     * Whether to keep the last values received. With false, nothing is read or written, even when
     * a [provider] is given. Defaults to true.
     */
    public val enabled: Boolean,

    /**
     * The store for the values, or null for the app's `SharedPreferences`, under a key derived
     * from the client SDK key, with a size limit of 256 KB.
     */
    public val provider: CachingProvider?,
) {

    override fun toString(): String = "CachingOptions(enabled=$enabled, provider=$provider)"

    /** Collects caching settings. Every setter returns this, so calls chain. */
    public class Builder internal constructor() {
        private var enabled: Boolean = true
        private var provider: CachingProvider? = null

        /** Whether to keep the last values received. Defaults to true. */
        public fun enabled(enabled: Boolean): Builder = apply { this.enabled = enabled }

        /** The store for the values. Null, the default, means the app's `SharedPreferences`. */
        public fun provider(provider: CachingProvider?): Builder = apply { this.provider = provider }

        /** Builds the settings. */
        public fun build(): CachingOptions = CachingOptions(enabled = enabled, provider = provider)
    }

    public companion object {
        private val DEFAULTS: CachingOptions = Builder().build()

        /** Starts from the defaults. */
        @JvmStatic
        public fun builder(): Builder = Builder()

        /** Keeping the last values received in the app's `SharedPreferences`. */
        @JvmStatic
        public fun defaults(): CachingOptions = DEFAULTS

        /**
         * Builds caching settings.
         *
         * ```kotlin
         * val caching = CachingOptions.build { enabled(false) }
         * ```
         */
        @JvmSynthetic
        public fun build(configure: Builder.() -> Unit): CachingOptions =
            Builder().apply(configure).build()
    }
}
