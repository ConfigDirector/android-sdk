package com.configdirector.internal

import android.content.Context
import android.content.SharedPreferences
import com.configdirector.ConfigDirectorLogger
import com.configdirector.debug
import com.configdirector.warn
import java.util.UUID
import org.json.JSONException
import org.json.JSONObject

internal class ClientSession(val instanceId: String, val ephemeral: Boolean)

internal interface InstanceIdStorage {
    fun read(): String?

    fun write(record: String): Boolean
}

private const val RECORD_VERSION = 1
internal const val PREFERENCES_NAME = "configdirector-sdk"
private const val INSTANCE_ID_KEY = "instance-id"
private val UUID_V4_PATTERN =
    Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

private sealed interface StoredInstanceId {
    class Current(val instanceId: String) : StoredInstanceId

    object NothingUsable : StoredInstanceId

    object Unreadable : StoredInstanceId
}

internal fun currentClientSession(storage: InstanceIdStorage, logger: ConfigDirectorLogger): ClientSession =
    when (val stored = storedInstanceId(storage, logger)) {
        is StoredInstanceId.Current -> ClientSession(stored.instanceId, ephemeral = false)
        StoredInstanceId.Unreadable -> ClientSession(UUID.randomUUID().toString(), ephemeral = true)
        StoredInstanceId.NothingUsable -> {
            val instanceId = UUID.randomUUID().toString()
            ClientSession(instanceId, ephemeral = !store(storage, instanceId, logger))
        }
    }

private fun storedInstanceId(storage: InstanceIdStorage, logger: ConfigDirectorLogger): StoredInstanceId {
    val raw = try {
        storage.read() ?: return StoredInstanceId.NothingUsable
    } catch (failure: Exception) {
        logger.warn(failure) { couldNot("read") }
        return StoredInstanceId.Unreadable
    }

    val record = try {
        JSONObject(raw)
    } catch (malformed: JSONException) {
        logger.debug { "The stored instance ID is not valid JSON, generating a new one" }
        return StoredInstanceId.NothingUsable
    }

    val instanceId = record.optString("id")
    val usable = record.optInt("v") == RECORD_VERSION &&
        UUID_V4_PATTERN.matches(instanceId) &&
        record.optLong("createdAt") > 0
    if (!usable) {
        logger.debug { "The stored instance ID failed validation, generating a new one" }
        return StoredInstanceId.NothingUsable
    }

    return StoredInstanceId.Current(instanceId)
}

private fun store(storage: InstanceIdStorage, instanceId: String, logger: ConfigDirectorLogger): Boolean {
    val record = JSONObject()
        .put("v", RECORD_VERSION)
        .put("id", instanceId)
        .put("createdAt", System.currentTimeMillis())
        .toString()
    val stored = try {
        storage.write(record)
    } catch (failure: Exception) {
        logger.warn(failure) { couldNot("store") }
        return false
    }

    if (!stored) logger.warn { couldNot("store") }
    return stored
}

private fun couldNot(action: String): String =
    "The ConfigDirector SDK could not $action its instance ID, so this app start looks like a new " +
        "install to ConfigDirector."

internal fun sharedPreferencesInstanceIdStorage(androidContext: Context): InstanceIdStorage =
    object : InstanceIdStorage {
        private val preferences: SharedPreferences
            get() = androidContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

        override fun read(): String? = preferences.getString(INSTANCE_ID_KEY, null)

        override fun write(record: String): Boolean =
            preferences.edit().putString(INSTANCE_ID_KEY, record).commit()
    }
