package com.configdirector.internal.transport

import com.configdirector.ConfigDirectorContext
import com.configdirector.internal.ConfigSet
import com.configdirector.internal.ConfigState
import com.configdirector.internal.ConfigType

/**
 * Retrieves config state from the ConfigDirector server and hands each set it receives to the
 * handler it was created with.
 */
internal interface Transport {
    /**
     * Connects using [context], returning once the connection is established or once
     * [timeoutMillis] elapses. Returning does not imply config state was received; that arrives on
     * the handler.
     */
    suspend fun connect(context: ConfigDirectorContext, timeoutMillis: Long)

    /** Drops the connection without releasing the transport, so [connect] can be called again. */
    fun disconnect()

    /** Drops the connection and releases every resource the transport holds. */
    fun close()
}
