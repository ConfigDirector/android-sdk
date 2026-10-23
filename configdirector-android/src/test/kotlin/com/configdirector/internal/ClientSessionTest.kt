package com.configdirector.internal

import com.configdirector.RecordingLogger
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test

class ClientSessionTest {

    private val logger = RecordingLogger()
    private val storage = RecordingInstanceIdStorage()

    private class RecordingInstanceIdStorage : InstanceIdStorage {
        var record: String? = null
        var readFailure: Exception? = null
        var writeFailure: Exception? = null
        var writeStores: Boolean = true
        val writes = mutableListOf<String>()

        override fun read(): String? {
            readFailure?.let { throw it }
            return record
        }

        override fun write(record: String): Boolean {
            writes += record
            writeFailure?.let { throw it }
            if (!writeStores) return false
            this.record = record
            return true
        }
    }

    private fun record(
        id: String = STORED_ID,
        createdAt: Long,
        version: Int = 1,
    ): String = JSONObject().put("v", version).put("id", id).put("createdAt", createdAt).toString()

    private fun storedRecord(): JSONObject = JSONObject(checkNotNull(storage.record))

    @Test
    fun `reuses the stored instance ID without rewriting it a year after it was generated`() {
        val createdAt = System.currentTimeMillis() - ONE_YEAR_MILLIS
        storage.record = record(createdAt = createdAt)

        val session = currentClientSession(storage, logger)

        assertThat(session.instanceId).isEqualTo(STORED_ID)
        assertThat(session.ephemeral).isFalse()
        assertThat(storage.writes).isEmpty()
        assertThat(storedRecord().getLong("createdAt")).isEqualTo(createdAt)
    }

    @Test
    fun `generates an instance ID and stores it with its creation time when nothing is stored`() {
        val before = System.currentTimeMillis()

        val session = currentClientSession(storage, logger)

        val after = System.currentTimeMillis()
        assertThat(session.instanceId).matches(UUID_V4_PATTERN)
        assertThat(session.ephemeral).isFalse()
        assertThat(storedRecord().getInt("v")).isEqualTo(1)
        assertThat(storedRecord().getString("id")).isEqualTo(session.instanceId)
        assertThat(storedRecord().getLong("createdAt")).isIn(Range.closed(before, after))
    }

    @Test
    fun `reuses the stored instance ID when its creation time is in the future`() {
        storage.record = record(createdAt = System.currentTimeMillis() + 60_000)

        val session = currentClientSession(storage, logger)

        assertThat(session.instanceId).isEqualTo(STORED_ID)
        assertThat(session.ephemeral).isFalse()
        assertThat(storage.writes).isEmpty()
    }

    @Test
    fun `generates a new instance ID when the stored creation time is not positive`() {
        storage.record = record(createdAt = 0)

        val session = currentClientSession(storage, logger)

        assertThat(session.instanceId).isNotEqualTo(STORED_ID)
        assertThat(storedRecord().getString("id")).isEqualTo(session.instanceId)
    }

    @Test
    fun `generates a new instance ID when the stored record has another version`() {
        storage.record = record(createdAt = System.currentTimeMillis(), version = 2)

        val session = currentClientSession(storage, logger)

        assertThat(session.instanceId).isNotEqualTo(STORED_ID)
        assertThat(storedRecord().getInt("v")).isEqualTo(1)
        assertThat(storedRecord().getString("id")).isEqualTo(session.instanceId)
    }

    @Test
    fun `generates a new instance ID when the stored one is not a UUID`() {
        storage.record = record(id = "instance-1", createdAt = System.currentTimeMillis())

        val session = currentClientSession(storage, logger)

        assertThat(session.instanceId).isNotEqualTo("instance-1")
        assertThat(storedRecord().getString("id")).isEqualTo(session.instanceId)
    }

    @Test
    fun `generates a new instance ID when the stored record cannot be read as JSON`() {
        storage.record = "{oops"

        val session = currentClientSession(storage, logger)

        assertThat(session.ephemeral).isFalse()
        assertThat(storedRecord().getString("id")).isEqualTo(session.instanceId)
    }

    @Test
    fun `uses an ephemeral instance ID without writing when the storage cannot be read`() {
        storage.record = record(createdAt = System.currentTimeMillis())
        storage.readFailure = IllegalStateException("preferences unavailable")

        val session = currentClientSession(storage, logger)

        assertThat(session.instanceId).matches(UUID_V4_PATTERN)
        assertThat(session.ephemeral).isTrue()
        assertThat(storage.writes).isEmpty()
        assertThat(logger.messagesContaining("WARN:")).hasSize(1)
    }

    @Test
    fun `uses an ephemeral instance ID when the storage reports the write did not happen`() {
        storage.writeStores = false

        val session = currentClientSession(storage, logger)

        assertThat(session.instanceId).matches(UUID_V4_PATTERN)
        assertThat(session.ephemeral).isTrue()
        assertThat(logger.messagesContaining("WARN:")).hasSize(1)
    }

    @Test
    fun `uses an ephemeral instance ID when writing to the storage throws`() {
        storage.writeFailure = IllegalStateException("disk full")

        val session = currentClientSession(storage, logger)

        assertThat(session.instanceId).matches(UUID_V4_PATTERN)
        assertThat(session.ephemeral).isTrue()
        assertThat(storage.record).isNull()
        assertThat(logger.messagesContaining("WARN:")).hasSize(1)
    }
}

private const val ONE_YEAR_MILLIS = 366 * 24 * 60 * 60 * 1_000L
private const val STORED_ID = "11111111-1111-4111-8111-111111111111"
private const val UUID_V4_PATTERN =
    "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
