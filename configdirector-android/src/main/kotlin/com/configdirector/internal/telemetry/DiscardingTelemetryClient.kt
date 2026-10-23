package com.configdirector.internal.telemetry

import com.configdirector.ConfigDirectorContext

internal object DiscardingTelemetryClient : TelemetryClient {
    override fun evaluatedConfig(event: EvaluatedConfigEvent) = Unit

    override fun recordInvalidContextSubmission(contextKindSample: String) = Unit

    override fun updateContext(context: ConfigDirectorContext?) = Unit

    override suspend fun flush() = Unit

    override fun close() = Unit
}
