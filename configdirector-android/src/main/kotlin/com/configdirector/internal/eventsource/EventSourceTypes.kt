package com.configdirector.internal.eventsource

internal class EventSourceMessage(
    val id: String?,
    val event: String?,
    val data: String,
)

/** What an [EventSourceClient] publishes while it is connected. */
internal sealed class EventSourceEvent {
    /** The connection opened. Published again after every reconnection. */
    data object Open : EventSourceEvent()

    data class Message(val message: EventSourceMessage) : EventSourceEvent()

    /** The connection ended, whether the server closed it or it failed. */
    data class Failure(val statusCode: Int?, val cause: Throwable?) : EventSourceEvent()
}

/** What the connection looked like when it ended, which decides whether and when to reconnect. */
internal class EventSourceReconnection(
    /**
     * 1 for the first attempt after a connection drops, growing while attempts keep failing, and
     * reset once a connection opens.
     */
    val attempt: Int,
    val statusCode: Int?,
    val cause: Throwable?,
)

internal class EventSourceException(message: String) : Exception(message)
