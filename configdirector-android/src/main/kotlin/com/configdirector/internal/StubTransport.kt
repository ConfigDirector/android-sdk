package com.configdirector.internal

import com.configdirector.ConfigDirectorContext

/**
 * Stands in for the real transports until they are implemented, serving one hard-coded config set
 * so that the client's behaviour can be exercised end to end.
 *
 * The values it serves depend on the context, the way a server's evaluation of targeting rules
 * does, so that a context update changes what watches and events report.
 */
internal class StubTransport(private val onConfigSet: (ConfigSet) -> Unit) : Transport {

    override suspend fun connect(context: ConfigDirectorContext, timeoutMillis: Long) {
        onConfigSet(configSetFor(context))
    }

    override fun disconnect() = Unit

    override fun close() = Unit

    private fun configSetFor(context: ConfigDirectorContext): ConfigSet {
        val isPro = context.traits?.get("plan") == "pro"
        val variant = if (isPro) "pro" else "free"
        return ConfigSet(
            listOf(
                ConfigState(
                    key = "dark-mode",
                    type = ConfigType.BOOLEAN,
                    value = isPro.toString(),
                    valueId = "dark-mode-$variant",
                ),
                ConfigState(
                    key = "welcome-message",
                    type = ConfigType.STRING,
                    value = "Hello, ${context.name ?: "there"}",
                    valueId = "welcome-message-$variant",
                ),
                ConfigState(
                    key = "max-items",
                    type = ConfigType.INTEGER,
                    value = if (isPro) "25" else "10",
                    valueId = "max-items-$variant",
                ),
                ConfigState(
                    key = "sample-rate",
                    type = ConfigType.FLOAT,
                    value = "0.25",
                    valueId = "sample-rate-only",
                ),
                ConfigState(
                    key = "theme",
                    type = ConfigType.JSON,
                    value = """{"primary":"#101010"}""",
                    valueId = "theme-only",
                ),
                ConfigState(key = "beta-banner", type = ConfigType.BOOLEAN, value = null),
            ).associateBy { it.key },
        )
    }
}
