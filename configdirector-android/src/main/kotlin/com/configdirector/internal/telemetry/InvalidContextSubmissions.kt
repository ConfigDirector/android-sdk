package com.configdirector.internal.telemetry

internal class InvalidContextSubmissions {

    private val lock = Any()
    private var countsByContextKindSample = LinkedHashMap<String, Int>()
    private var countBeyondSampleLimit = 0
    private var startTime: Long? = null

    fun record(contextKindSample: String) {
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

    fun takeSummaries(): List<InvalidContextSubmissionSummary> = synchronized(lock) {
        val endTime = System.currentTimeMillis()
        val startTime = this.startTime ?: endTime
        val summariesByContextKindSample = countsByContextKindSample.map { (contextKindSample, count) ->
            InvalidContextSubmissionSummary(startTime, endTime, count, contextKindSample)
        }
        val summaryBeyondSampleLimit = if (countBeyondSampleLimit > 0) {
            listOf(
                InvalidContextSubmissionSummary(startTime, endTime, countBeyondSampleLimit, contextKindSample = null),
            )
        } else {
            emptyList()
        }

        clearLocked()

        summariesByContextKindSample + summaryBeyondSampleLimit
    }

    fun clear() {
        synchronized(lock) { clearLocked() }
    }

    private fun clearLocked() {
        countsByContextKindSample = LinkedHashMap()
        countBeyondSampleLimit = 0
        startTime = null
    }

    private companion object {
        private const val DISTINCT_CONTEXT_KIND_SAMPLE_LIMIT = 20
    }
}
