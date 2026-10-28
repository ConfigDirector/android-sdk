package com.configdirector.internal.transport

import com.configdirector.ConfigDirectorContext
import com.configdirector.ConnectReason
import com.configdirector.internal.ConfigSet
import com.configdirector.internal.ConfigState
import com.configdirector.internal.ConfigType

/** Retrieves config state from the ConfigDirector server. */
internal interface Transport {
    /**
     * Connects using [context], returning once the connection is established or once
     * [timeoutMillis] elapses. [reason] is what prompted the attempt. Returning does not imply
     * config state was received.
     */
    suspend fun connect(
        context: ConfigDirectorContext,
        timeoutMillis: Long,
        reason: ConnectReason,
        onConfigSet: (ConfigSet) -> Unit,
    )

    /** Drops the connection without releasing the transport, so [connect] can be called again. */
    fun disconnect()

    /** Drops the connection and releases every resource the transport holds. */
    fun close()
}
