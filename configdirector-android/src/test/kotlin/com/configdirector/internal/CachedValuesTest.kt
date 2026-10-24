package com.configdirector.internal

import com.configdirector.ConfigDirectorContext
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test

class CachedValuesTest {

    private val configs = mapOf(
        "dark-mode" to ConfigState("dark-mode", ConfigType.BOOLEAN, "true", id = "dark-mode-id", valueId = "dark-mode-pro"),
        "beta-banner" to ConfigState("beta-banner", ConfigType.BOOLEAN, null, id = "beta-banner-id", valueId = null),
        "theme" to ConfigState("theme", ConfigType.JSON, """{"primary":"#101010"}""", id = "theme-id", valueId = "theme-only"),
    )

    private val cachedValues = CachedValues(
        contextTag = ContextTag.Identified(contextKind = "user", contextId = "user-123"),
        environmentId = "env-1",
        projectId = "project-1",
        configs = configs,
        receivedAt = 1_761_000_000_000,
    )

    @Test
    fun `tags a context by its kind and identifier`() {
        val context = ConfigDirectorContext.build {
            kind("device")
            id("device-7")
            name("Ada")
            trait("plan", "pro")
        }

        val tag = contextTagFor(context, instanceId = INSTANCE_ID)

        assertThat(tag).isEqualTo(ContextTag.Identified(contextKind = "device", contextId = "device-7"))
    }

    @Test
    fun `tags a context without an identifier as anonymous with the instance ID`() {
        val tag = contextTagFor(ConfigDirectorContext.build { trait("plan", "pro") }, instanceId = INSTANCE_ID)

        assertThat(tag).isEqualTo(ContextTag.Anonymous(contextKind = "user", instanceId = INSTANCE_ID))
    }

    @Test
    fun `tags no context as an anonymous user with the instance ID`() {
        val tag = contextTagFor(null, instanceId = INSTANCE_ID)

        assertThat(tag).isEqualTo(ContextTag.Anonymous(contextKind = "user", instanceId = INSTANCE_ID))
    }

    @Test
    fun `serializes an entry it reads back as the same cached values`() {
        val serialized = serializeCachedValuesEntry(CLIENT_SDK_KEY, cachedValues)

        val entry = JSONObject(serialized)
        assertThat(entry.getInt("formatVersion")).isEqualTo(1)
        assertThat(entry.getString("clientSdkKey")).isEqualTo(CLIENT_SDK_KEY)
        assertThat(entry.getJSONObject("contextTag").getString("contextKind")).isEqualTo("user")
        assertThat(entry.getJSONObject("contextTag").getString("contextId")).isEqualTo("user-123")
        assertThat(entry.getString("environmentId")).isEqualTo("env-1")
        assertThat(entry.getString("projectId")).isEqualTo("project-1")
        assertThat(entry.getLong("receivedAt")).isEqualTo(1_761_000_000_000)
        val darkMode = entry.getJSONObject("configs").getJSONObject("dark-mode")
        assertThat(darkMode.getString("id")).isEqualTo("dark-mode-id")
        assertThat(darkMode.getString("type")).isEqualTo("boolean")
        assertThat(darkMode.getString("value")).isEqualTo("true")
        assertThat(darkMode.getString("valueId")).isEqualTo("dark-mode-pro")
        assertThat(entry.getJSONObject("configs").getJSONObject("beta-banner").isNull("value")).isTrue()
        assertThat(cachedValuesIn(serialized, CLIENT_SDK_KEY)).isEqualTo(cachedValues)
    }

    @Test
    fun `serializes an anonymous context tag with the instance ID`() {
        val anonymous = cachedValues.copy(contextTag = ContextTag.Anonymous(contextKind = "device", instanceId = INSTANCE_ID))

        val serialized = serializeCachedValuesEntry(CLIENT_SDK_KEY, anonymous)

        val tag = JSONObject(serialized).getJSONObject("contextTag")
        assertThat(tag.getString("contextKind")).isEqualTo("device")
        assertThat(tag.getString("anonymousInstanceId")).isEqualTo(INSTANCE_ID)
        assertThat(tag.has("contextId")).isFalse()
        assertThat(cachedValuesIn(serialized, CLIENT_SDK_KEY)).isEqualTo(anonymous)
    }

    @Test
    fun `reads no cached values from an entry with another format version`() {
        val entry = JSONObject(serializeCachedValuesEntry(CLIENT_SDK_KEY, cachedValues)).put("formatVersion", 2)

        assertThat(cachedValuesIn(entry.toString(), CLIENT_SDK_KEY)).isNull()
    }

    @Test
    fun `reads no cached values from an entry written for another client SDK key`() {
        val serialized = serializeCachedValuesEntry("another-client-sdk-key", cachedValues)

        assertThat(cachedValuesIn(serialized, CLIENT_SDK_KEY)).isNull()
    }

    @Test
    fun `reads no cached values from text that is not JSON`() {
        assertThat(cachedValuesIn("{oops", CLIENT_SDK_KEY)).isNull()
    }

    @Test
    fun `reads no cached values from an entry without a context tag`() {
        assertThat(cachedValuesIn("""{"formatVersion":1,"clientSdkKey":"$CLIENT_SDK_KEY"}""", CLIENT_SDK_KEY)).isNull()
    }

    @Test
    fun `adopts cached values for the same context before live values arrive`() {
        val adoption = decideCachedValuesAdoption(
            cachedValues,
            launchContextTag = ContextTag.Identified("user", "user-123"),
            hasReceivedLiveValues = false,
            isClosed = false,
        )

        assertThat(adoption).isEqualTo(CachedValuesAdoption.ADOPT)
    }

    @Test
    fun `does not adopt cached values once live values arrived`() {
        val adoption = decideCachedValuesAdoption(
            cachedValues,
            launchContextTag = ContextTag.Identified("user", "user-123"),
            hasReceivedLiveValues = true,
            isClosed = false,
        )

        assertThat(adoption).isEqualTo(CachedValuesAdoption.LIVE_VALUES_ALREADY_ARRIVED)
    }

    @Test
    fun `does not adopt cached values tagged with another context`() {
        val adoption = decideCachedValuesAdoption(
            cachedValues,
            launchContextTag = ContextTag.Identified("user", "user-456"),
            hasReceivedLiveValues = false,
            isClosed = false,
        )

        assertThat(adoption).isEqualTo(CachedValuesAdoption.CONTEXT_MISMATCH)
    }

    @Test
    fun `does not adopt cached values tagged with another context kind`() {
        val adoption = decideCachedValuesAdoption(
            cachedValues,
            launchContextTag = ContextTag.Identified("device", "user-123"),
            hasReceivedLiveValues = false,
            isClosed = false,
        )

        assertThat(adoption).isEqualTo(CachedValuesAdoption.CONTEXT_MISMATCH)
    }

    @Test
    fun `does not adopt cached values on a closed client`() {
        val adoption = decideCachedValuesAdoption(
            cachedValues,
            launchContextTag = ContextTag.Identified("user", "user-123"),
            hasReceivedLiveValues = false,
            isClosed = true,
        )

        assertThat(adoption).isEqualTo(CachedValuesAdoption.CLIENT_CLOSED)
    }
}

private const val CLIENT_SDK_KEY = "client-sdk-key"
private const val INSTANCE_ID = "11111111-1111-4111-8111-111111111111"
