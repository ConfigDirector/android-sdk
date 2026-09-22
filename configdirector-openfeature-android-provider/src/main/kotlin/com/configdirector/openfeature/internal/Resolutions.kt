package com.configdirector.openfeature.internal

import com.configdirector.ConfigEvaluation
import com.configdirector.EvaluationReason
import dev.openfeature.kotlin.sdk.ProviderEvaluation
import dev.openfeature.kotlin.sdk.Reason
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode

internal fun <T> ConfigEvaluation.toProviderEvaluation(value: T): ProviderEvaluation<T> = when (reason) {
    EvaluationReason.FOUND_MATCH ->
        ProviderEvaluation(value, variant = valueId, reason = Reason.TARGETING_MATCH.name)

    EvaluationReason.VALUE_MISSING -> ProviderEvaluation(value, reason = Reason.DEFAULT.name)

    EvaluationReason.CONFIG_STATE_MISSING ->
        error(value, ErrorCode.FLAG_NOT_FOUND, "No config with the key '$key' was found.")

    EvaluationReason.CLIENT_NOT_READY ->
        error(value, ErrorCode.PROVIDER_NOT_READY, "ConfigDirector has not delivered config state yet.")

    EvaluationReason.TYPE_MISMATCH,
    EvaluationReason.INVALID_BOOLEAN,
    EvaluationReason.INVALID_NUMBER,
    EvaluationReason.INVALID_JSON,
    ->
        error(
            value,
            ErrorCode.TYPE_MISMATCH,
            "The value of '$key' cannot be read as the requested type (${reason.wireName}).",
        )
}

internal fun <T> unsupportedDefault(value: T, message: String): ProviderEvaluation<T> =
    error(value, ErrorCode.TYPE_MISMATCH, message)

private fun <T> error(value: T, errorCode: ErrorCode, message: String): ProviderEvaluation<T> =
    ProviderEvaluation(value, reason = Reason.ERROR.name, errorCode = errorCode, errorMessage = message)
