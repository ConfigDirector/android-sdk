package com.configdirector.internal.transport

import com.configdirector.ConfigDirectorContext
import com.configdirector.ConnectReason
import com.configdirector.internal.ConfigSet
import com.configdirector.internal.ConfigSetKind
import com.configdirector.internal.ConfigState

internal class OfflineTransport(
    private val configs: Map<String, ConfigState>,
    private val onConfigSet: (ConfigSet) -> Unit,
) : Transport {
    override suspend fun connect(context: ConfigDirectorContext, timeoutMillis: Long, reason: ConnectReason) {
        onConfigSet(ConfigSet(configs, ConfigSetKind.FULL))
    }

    override fun disconnect() = Unit

    override fun close() = Unit
}
