package com.configdirector.internal.telemetry

/** Everything an [EventQueue] had collected when a report was prepared. */
internal data class EventQueueSnapshot(
    /** When the first of the [events] was collected. */
    val startTime: Long,
    /** When the snapshot was taken. */
    val endTime: Long,
    val events: List<EvaluatedConfigEvent>,
    /** How many events were dropped because the queue was full. */
    val droppedCount: Int,
) {
    val isEmpty: Boolean get() = events.isEmpty() && droppedCount == 0

    /**
     * Collapses identical events into one entry each, carrying how many times the event occurred
     * over the window this snapshot covers.
     */
    fun aggregated(): List<AggregatedEvent> =
        events.groupingBy { it }.eachCount()
            .map { (event, count) -> AggregatedEvent(startTime, endTime, count, event) }
}

/**
 * Holds collected events until they are reported.
 *
 * The queue is bounded: once it is full the oldest events are dropped to make room for new ones,
 * and the number dropped is reported alongside the events that were kept.
 */
internal class EventQueue(limit: Int = DEFAULT_LIMIT) {

    private val limit = limit.coerceAtLeast(1)
    private val lock = Any()
    private var events = ArrayDeque<EvaluatedConfigEvent>()
    private var startTime: Long? = null
    private var droppedCount = 0

    val isEmpty: Boolean
        get() = synchronized(lock) { events.isEmpty() && droppedCount == 0 }

    val pendingCount: Int
        get() = synchronized(lock) { events.size }

    fun push(event: EvaluatedConfigEvent) {
        synchronized(lock) {
            if (startTime == null) {
                startTime = System.currentTimeMillis()
            }
            while (events.size >= limit) {
                events.removeFirst()
                droppedCount += 1
            }
            events.addLast(event)
        }
    }

    /** Empties the queue and returns what it held, ready to collect the next batch. */
    fun takeSnapshot(): EventQueueSnapshot = synchronized(lock) {
        val endTime = System.currentTimeMillis()
        val snapshot = EventQueueSnapshot(startTime ?: endTime, endTime, events.toList(), droppedCount)

        events = ArrayDeque()
        startTime = null
        droppedCount = 0

        snapshot
    }

    fun clear() {
        synchronized(lock) {
            events = ArrayDeque()
            startTime = null
            droppedCount = 0
        }
    }

    private companion object {
        private const val DEFAULT_LIMIT = 1_000
    }
}
