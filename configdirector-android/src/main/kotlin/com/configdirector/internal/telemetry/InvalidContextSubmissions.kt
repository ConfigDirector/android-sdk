package com.configdirector.internal.telemetry

import com.configdirector.internal.ContextSizeLimit

internal class InvalidContextSubmissions {

    private val lock = Any()
    private var countsByContextKindSample = LinkedHashMap<String, Int>()
    private var countBeyondSampleLimit = 0
    private var countsBySizeLimit = LinkedHashMap<ContextSizeLimit, Int>()
    private var startTime: Long? = null

    fun recordInvalidContextKind(contextKindSample: String) {
        synchronized(lock) {
            if (startTime == null) {
                startTime = System.currentTimeMillis()
            }
            val knownCount = countsByContextKindSample[contextKindSample]
            when {
                knownCount != null -> countsByContextKindSample[contextKindSample] = knownCount + 1
                countsByContextKindSample.size < DISTINCT_CONTEXT_KIND_SAMPLE_LIMIT ->
                    countsByContextKindSample[contextKindSample] = 1
                else -> countBeyondSampleLimit += 1
            }
        }
    }

    fun recordOverSizeLimit(limit: ContextSizeLimit) {
        synchronized(lock) {
            if (startTime == null) {
                startTime = System.currentTimeMillis()
            }
            countsBySizeLimit[limit] = (countsBySizeLimit[limit] ?: 0) + 1
        }
    }

    fun takeSummaries(): List<InvalidContextSubmissionSummary> = synchronized(lock) {
        val endTime = System.currentTimeMillis()
        val startTime = this.startTime ?: endTime
        fun summaryOf(count: Int, event: InvalidContextEvent) =
            InvalidContextSubmissionSummary(startTime, endTime, count, event)

        val summariesByContextKindSample = countsByContextKindSample.map { (contextKindSample, count) ->
            summaryOf(count, InvalidContextEvent.InvalidContextKind(contextKindSample))
        }
        val summaryBeyondSampleLimit = if (countBeyondSampleLimit > 0) {
            listOf(summaryOf(countBeyondSampleLimit, InvalidContextEvent.InvalidContextKind(contextKindSample = null)))
        } else {
            emptyList()
        }
        val summariesBySizeLimit = countsBySizeLimit.map { (limit, count) ->
            summaryOf(count, InvalidContextEvent.OverASizeLimit(limit))
        }

        clearLocked()

        summariesByContextKindSample + summaryBeyondSampleLimit + summariesBySizeLimit
    }

    fun clear() {
        synchronized(lock) { clearLocked() }
    }

    private fun clearLocked() {
        countsByContextKindSample = LinkedHashMap()
        countBeyondSampleLimit = 0
        countsBySizeLimit = LinkedHashMap()
        startTime = null
    }

    private companion object {
        private const val DISTINCT_CONTEXT_KIND_SAMPLE_LIMIT = 20
    }
}
