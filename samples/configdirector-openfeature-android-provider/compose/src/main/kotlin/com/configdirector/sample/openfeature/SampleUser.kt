package com.configdirector.sample.openfeature

import dev.openfeature.kotlin.sdk.EvaluationContext
import dev.openfeature.kotlin.sdk.ImmutableContext
import dev.openfeature.kotlin.sdk.Value

/**
 * The identities the sample can evaluate flags against. Switching between them calls
 * `OpenFeatureAPI.setEvaluationContext`, which has the provider reconnect and re-evaluate every
 * flag.
 *
 * The provider sends the targeting key as the user's id, `name` as the name, the `traits`
 * structure as the traits targeting rules are written against, and `anonymous` as the anonymous
 * flag.
 */
enum class SampleUser(val label: String, val context: EvaluationContext) {
    CONFIGURED("Configured", configuredContext()),

    BETA_TESTER(
        "Beta tester",
        ImmutableContext(
            targetingKey = "beta-tester",
            attributes = mapOf(
                "name" to Value.String("Beta Tester"),
                "traits" to Value.Structure(mapOf("role" to Value.String("beta"))),
            ),
        ),
    ),

    ANONYMOUS("Anonymous", ImmutableContext(attributes = mapOf("anonymous" to Value.Boolean(true)))),
}

/** The identity from `local.properties`. With none set, flags are evaluated without a context. */
private fun configuredContext(): EvaluationContext {
    val attributes = buildMap {
        BuildConfig.USER_NAME.ifEmpty { null }?.let { put("name", Value.String(it)) }
        BuildConfig.USER_ROLE.ifEmpty { null }?.let {
            put("traits", Value.Structure(mapOf("role" to Value.String(it))))
        }
    }
    return ImmutableContext(targetingKey = BuildConfig.USER_ID, attributes = attributes)
}
