package com.configdirector.openfeature.internal

import com.configdirector.ConfigEvaluation
import com.configdirector.EvaluationReason
import dev.openfeature.kotlin.sdk.ProviderEvaluation
import dev.openfeature.kotlin.sdk.Reason
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError

internal fun <T> ConfigEvaluation.toProviderEvaluation(value: T): ProviderEvaluation<T> = when (reason) {
    EvaluationReason.FOUND_MATCH ->
        ProviderEvaluation(value, variant = valueId, reason = Reason.TARGETING_MATCH.name)

    EvaluationReason.CACHED -> ProviderEvaluation(value, variant = valueId, reason = Reason.CACHED.name)

    EvaluationReason.VALUE_MISSING -> ProviderEvaluation(value, reason = Reason.DEFAULT.name)

    EvaluationReason.CONFIG_STATE_MISSING ->
        throw OpenFeatureError.FlagNotFoundError(key, "No config with the key '$key' was found.")

    EvaluationReason.CLIENT_NOT_READY ->
        throw OpenFeatureError.ProviderNotReadyError("ConfigDirector has not delivered config state yet.")

    EvaluationReason.TYPE_MISMATCH,
    EvaluationReason.INVALID_BOOLEAN,
    EvaluationReason.INVALID_NUMBER,
    EvaluationReason.INVALID_JSON,
    ->
        throw OpenFeatureError.TypeMismatchError(
            "The value of '$key' cannot be read as the requested type (${reason.wireName}).",
        )
}
