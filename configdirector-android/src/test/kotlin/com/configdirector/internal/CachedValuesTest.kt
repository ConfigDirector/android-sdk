package com.configdirector.internal

import com.configdirector.ConfigDirectorContext
import com.configdirector.Variation
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
        val serialized = serializeCachedValuesEntry(CLIENT_SDK_KEY_DIGEST, cachedValues)

        val entry = JSONObject(serialized)
        assertThat(entry.getInt("formatVersion")).isEqualTo(2)
        assertThat(entry.getString("clientSdkKeyDigest")).isEqualTo(CLIENT_SDK_KEY_DIGEST)
        assertThat(entry.has("clientSdkKey")).isFalse()
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
        assertThat(parseCachedValuesEntry(serialized, CLIENT_SDK_KEY_DIGEST)).isEqualTo(CachedValuesRead.Stored(cachedValues))
    }

    @Test
    fun `keeps the variation, rule and share of a config in an entry`() {
        val experiment = ConfigState(
            "checkout-cta",
            ConfigType.STRING,
            "Try it free",
            id = "checkout-cta-id",
            valueId = "checkout-cta-value",
            variation = Variation("variation-1", "treatment-a"),
            ruleId = "rule-1",
            shareId = "share-1",
        )
        val experimentValues = cachedValues.copy(configs = mapOf("checkout-cta" to experiment))

        val serialized = serializeCachedValuesEntry(CLIENT_SDK_KEY_DIGEST, experimentValues)

        val config = JSONObject(serialized).getJSONObject("configs").getJSONObject("checkout-cta")
        assertThat(config.getJSONObject("variation").getString("id")).isEqualTo("variation-1")
        assertThat(config.getJSONObject("variation").getString("name")).isEqualTo("treatment-a")
        assertThat(config.getString("ruleId")).isEqualTo("rule-1")
        assertThat(config.getString("shareId")).isEqualTo("share-1")
        assertThat(parseCachedValuesEntry(serialized, CLIENT_SDK_KEY_DIGEST)).isEqualTo(CachedValuesRead.Stored(experimentValues))
    }

    @Test
    fun `leaves the variation, rule and share out of the entry of a flag`() {
        val serialized = serializeCachedValuesEntry(CLIENT_SDK_KEY_DIGEST, cachedValues)

        val flag = JSONObject(serialized).getJSONObject("configs").getJSONObject("dark-mode")
        assertThat(flag.has("variation")).isFalse()
        assertThat(flag.has("ruleId")).isFalse()
        assertThat(flag.has("shareId")).isFalse()
    }

    @Test
    fun `keeps an unnamed variation in an entry with its id and no name`() {
        val experiment = ConfigState(
            "checkout-cta",
            ConfigType.STRING,
            "Try it free",
            id = "checkout-cta-id",
            valueId = "checkout-cta-value",
            variation = Variation("variation-1"),
        )
        val experimentValues = cachedValues.copy(configs = mapOf("checkout-cta" to experiment))

        val serialized = serializeCachedValuesEntry(CLIENT_SDK_KEY_DIGEST, experimentValues)

        assertThat(parseCachedValuesEntry(serialized, CLIENT_SDK_KEY_DIGEST)).isEqualTo(CachedValuesRead.Stored(experimentValues))
    }

    @Test
    fun `reads an entry stored before entries carried a variation, rule or share`() {
        val read = parseCachedValuesEntry(ENTRY_WITHOUT_VARIATIONS, CLIENT_SDK_KEY_DIGEST)

        val config = (read as CachedValuesRead.Stored).cachedValues.configs.getValue("checkout-cta")
        assertThat(config.value).isEqualTo("Try it free")
        assertThat(config.valueId).isEqualTo("checkout-cta-value")
        assertThat(config.variation).isNull()
        assertThat(config.ruleId).isNull()
        assertThat(config.shareId).isNull()
    }

    @Test
    fun `serializes an anonymous context tag with the instance ID`() {
        val anonymous = cachedValues.copy(contextTag = ContextTag.Anonymous(contextKind = "device", instanceId = INSTANCE_ID))

        val serialized = serializeCachedValuesEntry(CLIENT_SDK_KEY_DIGEST, anonymous)

        val tag = JSONObject(serialized).getJSONObject("contextTag")
        assertThat(tag.getString("contextKind")).isEqualTo("device")
        assertThat(tag.getString("anonymousInstanceId")).isEqualTo(INSTANCE_ID)
        assertThat(tag.has("contextId")).isFalse()
        assertThat(parseCachedValuesEntry(serialized, CLIENT_SDK_KEY_DIGEST)).isEqualTo(CachedValuesRead.Stored(anonymous))
    }

    @Test
    fun `reads the entry an earlier release kept as a first format entry`() {
        assertThat(parseCachedValuesEntry(FIRST_FORMAT_ENTRY, CLIENT_SDK_KEY_DIGEST)).isEqualTo(CachedValuesRead.FirstFormatEntry)
    }

    @Test
    fun `reads no cached values from an entry with another format version`() {
        val entry = JSONObject(serializeCachedValuesEntry(CLIENT_SDK_KEY_DIGEST, cachedValues)).put("formatVersion", 3)

        assertThat(parseCachedValuesEntry(entry.toString(), CLIENT_SDK_KEY_DIGEST)).isEqualTo(CachedValuesRead.NotAnEntry)
    }

    @Test
    fun `reads no cached values from an entry written for another client SDK key`() {
        val serialized = serializeCachedValuesEntry(ANOTHER_CLIENT_SDK_KEY_DIGEST, cachedValues)

        assertThat(parseCachedValuesEntry(serialized, CLIENT_SDK_KEY_DIGEST)).isEqualTo(CachedValuesRead.NotAnEntry)
    }

    @Test
    fun `reads no cached values from text that is not JSON`() {
        assertThat(parseCachedValuesEntry("{oops", CLIENT_SDK_KEY_DIGEST)).isEqualTo(CachedValuesRead.NotAnEntry)
    }

    @Test
    fun `reads no cached values from an entry without a context tag`() {
        val bareEntry = """{"formatVersion":2,"clientSdkKeyDigest":"$CLIENT_SDK_KEY_DIGEST"}"""

        assertThat(parseCachedValuesEntry(bareEntry, CLIENT_SDK_KEY_DIGEST)).isEqualTo(CachedValuesRead.NotAnEntry)
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

private const val CLIENT_SDK_KEY_DIGEST = "24913e1336d021b669358fca9ac7212a3a52d53f77b98dca11ecb0e4441ab498"
private const val ANOTHER_CLIENT_SDK_KEY_DIGEST = "1b7c1cc1b967c42801d6886d061d42850d4a0fa49c5670403917512f9ccbde8b"
private const val FIRST_FORMAT_ENTRY =
    """{"formatVersion":1,"clientSdkKey":"client-sdk-key","contextTag":{"contextKind":"user","contextId":"user-123"},""" +
        """"environmentId":"env-1","projectId":"project-1","configs":{"dark-mode":{"id":"dark-mode-id","key":"dark-mode",""" +
        """"type":"boolean","value":"true","valueId":"dark-mode-pro"}},"receivedAt":1761000000000}"""
private const val ENTRY_WITHOUT_VARIATIONS =
    """{"formatVersion":2,"clientSdkKeyDigest":"$CLIENT_SDK_KEY_DIGEST","contextTag":{"contextKind":"user","contextId":"user-123"},""" +
        """"environmentId":"env-1","projectId":"project-1","configs":{"checkout-cta":{"id":"checkout-cta-id","key":"checkout-cta",""" +
        """"type":"string","value":"Try it free","valueId":"checkout-cta-value"}},"receivedAt":1761000000000}"""
private const val INSTANCE_ID = "11111111-1111-4111-8111-111111111111"
