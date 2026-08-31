package com.configdirector.internal

import com.configdirector.ConfigDirectorContext

/**
 * Stands in for the real transports until they are implemented, serving one hard-coded config set
 * so that the client's behaviour can be exercised end to end.
 */
internal class StubTransport(private val onConfigSet: (ConfigSet) -> Unit) : Transport {

    override suspend fun connect(context: ConfigDirectorContext, timeoutMillis: Long) {
        onConfigSet(STUB_CONFIG_SET)
    }

    override fun disconnect() = Unit

    override fun close() = Unit

    private companion object {
        private val STUB_CONFIG_SET = ConfigSet(
            listOf(
                ConfigState("dark-mode", ConfigType.BOOLEAN, "true", valueId = "value-dark-mode"),
                ConfigState("welcome-message", ConfigType.STRING, "Hello from ConfigDirector"),
                ConfigState("max-items", ConfigType.INTEGER, "25"),
                ConfigState("sample-rate", ConfigType.FLOAT, "0.25"),
                ConfigState("theme", ConfigType.JSON, """{"primary":"#101010"}"""),
                ConfigState("beta-banner", ConfigType.BOOLEAN, null),
            ).associateBy { it.key },
        )
    }
}
