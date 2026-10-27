package com.configdirector.internal

import android.content.Context
import android.content.SharedPreferences
import com.configdirector.CachingProvider

private const val CACHED_VALUES_KEY_PREFIX = "cached-values:"
private const val SIZE_LIMIT_BYTES = 256 * 1024

internal class SharedPreferencesCachingProvider(
    private val androidContext: Context,
    clientSdkKeyDigest: String,
) : CachingProvider {
    private val key = CACHED_VALUES_KEY_PREFIX + clientSdkKeyDigest

    private val preferences: SharedPreferences
        get() = androidContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override suspend fun read(): String? = preferences.getString(key, null)

    override suspend fun write(value: String) {
        if (!preferences.edit().putString(key, value).commit()) {
            throw IllegalStateException("SharedPreferences did not store the entry")
        }
    }

    override suspend fun remove() {
        if (!preferences.edit().remove(key).commit()) {
            throw IllegalStateException("SharedPreferences did not remove the entry")
        }
    }

    override fun sizeLimit(): Int = SIZE_LIMIT_BYTES
}
