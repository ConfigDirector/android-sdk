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
        return ConfigSet(
            listOf(
                ConfigState(
                    key = "dark-mode",
                    type = ConfigType.BOOLEAN,
                    value = isPro.toString(),
                    valueId = "dark-mode-$isPro",
                ),
                ConfigState(
                    key = "welcome-message",
                    type = ConfigType.STRING,
                    value = "Hello, ${context.name ?: "there"}",
                ),
                ConfigState(key = "max-items", type = ConfigType.INTEGER, value = if (isPro) "25" else "10"),
                ConfigState(key = "sample-rate", type = ConfigType.FLOAT, value = "0.25"),
                ConfigState(key = "theme", type = ConfigType.JSON, value = """{"primary":"#101010"}"""),
                ConfigState(key = "beta-banner", type = ConfigType.BOOLEAN, value = null),
            ).associateBy { it.key },
        )
    }
}
