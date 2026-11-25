package com.configdirector.internal.telemetry

import com.configdirector.ConfigDirectorContext
import com.configdirector.internal.ContextSizeLimit

internal object DiscardingTelemetryClient : TelemetryClient {
    override fun evaluatedConfig(event: EvaluatedConfigEvent) = Unit

    override fun recordInvalidContextKind(contextKindSample: String) = Unit

    override fun recordContextOverSizeLimit(limit: ContextSizeLimit) = Unit

    override fun trackedEvent(admission: TrackedEventAdmission) = Unit

    override fun updateContext(context: ConfigDirectorContext?) = Unit

    override suspend fun flush() = Unit

    override fun close() = Unit
}
