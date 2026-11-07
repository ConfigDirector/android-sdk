package com.configdirector.internal.testing

import com.configdirector.ConfigDirectorContext
import com.configdirector.ConfigDirectorLogger
import com.configdirector.ConfigDirectorTestingApi
import com.configdirector.internal.telemetry.EvaluatedConfigEvent
import com.configdirector.internal.telemetry.TelemetryClient
import com.configdirector.internal.telemetry.TrackedEventAdmission
import com.configdirector.warn

@OptIn(ConfigDirectorTestingApi::class)
internal class RecordingTelemetryClient(
    private val logger: ConfigDirectorLogger,
    private val recorder: TrackedEventRecorder,
) : TelemetryClient {

    override fun trackedEvent(admission: TrackedEventAdmission) {
        admission.warnings.forEach { warning -> logger.warn { warning.message } }

        when (admission) {
            is TrackedEventAdmission.Dropped -> Unit
            is TrackedEventAdmission.Admitted -> recorder.record(
                admission.entry.name,
                admission.context,
                admission.entry.value,
                admission.entry.details,
            )
        }
    }

    override fun evaluatedConfig(event: EvaluatedConfigEvent) = Unit

    override fun recordInvalidContextSubmission(contextKindSample: String) = Unit

    override fun updateContext(context: ConfigDirectorContext?) = Unit

    override suspend fun flush() = Unit

    override fun close() = Unit
}
