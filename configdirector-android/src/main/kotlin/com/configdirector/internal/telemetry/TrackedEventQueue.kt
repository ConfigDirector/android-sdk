package com.configdirector.internal.telemetry

import java.util.EnumSet

internal class TrackedEventQueue {

    private val lock = Any()
    private var entries = ArrayList<TrackedEventEntry>()
    private val warningsRaisedThisBatch = EnumSet.noneOf(TrackedEventWarning::class.java)

    fun add(admission: TrackedEventAdmission): List<TrackedEventWarning> = synchronized(lock) {
        warningsOfKeeping(admission).filter(warningsRaisedThisBatch::add)
    }

    fun takeBatch(): List<TrackedEventEntry> = synchronized(lock) {
        val batch = entries
        clearLocked()
        batch
    }

    fun clear() {
        synchronized(lock) { clearLocked() }
    }

    private fun warningsOfKeeping(admission: TrackedEventAdmission): List<TrackedEventWarning> = when (admission) {
        is TrackedEventAdmission.Dropped -> admission.warnings
        is TrackedEventAdmission.Admitted -> if (entries.size >= TRACKED_EVENTS_PER_BATCH) {
            listOf(TrackedEventWarning.BATCH_ALREADY_FULL)
        } else {
            entries.add(admission.entry)
            admission.warnings
        }
    }

    private fun clearLocked() {
        entries = ArrayList()
        warningsRaisedThisBatch.clear()
    }
}
