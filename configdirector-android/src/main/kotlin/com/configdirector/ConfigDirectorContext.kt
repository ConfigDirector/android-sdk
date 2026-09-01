package com.configdirector

import java.util.Collections
import java.util.IdentityHashMap

/**
 * The user's context, sent to ConfigDirector and evaluated against targeting rules.
 *
 * ```java
 * ConfigDirectorContext context = ConfigDirectorContext.builder()
 *     .id("user-123")
 *     .trait("plan", "pro")
 *     .build();
 * ```
 */
public class ConfigDirectorContext private constructor(
    /**
     * The user's identifier, which should uniquely identify an application user.
     *
     * For anonymous users you may generate a UUID, or leave this unset and let the SDK generate a
     * random one. This value segments users in percentage rollouts, so changing it can move a user
     * into a different percentile.
     */
    public val id: String?,

    /** The user's display name, shown in the ConfigDirector dashboard. */
    public val name: String?,

    /**
     * Arbitrary traits for the current user, shown in the ConfigDirector dashboard and available to
     * targeting rules. Values are JSON-shaped: `String`, `Number`, `Boolean`, `List`, `Map`, or
     * null.
     */
    public val traits: Map<String, Any?>?,

    /**
     * Whether to treat this context as anonymous during evaluation. When true the values still
     * evaluate targeting rules, but the context is not persisted and does not appear in the
     * dashboard.
     */
    public val isAnonymous: Boolean,
) {

    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is ConfigDirectorContext &&
                    id == other.id &&
                    name == other.name &&
                    traits == other.traits &&
                    isAnonymous == other.isAnonymous
                )

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + traits.hashCode()
        result = 31 * result + isAnonymous.hashCode()
        return result
    }

    override fun toString(): String =
        "ConfigDirectorContext(id=$id, name=$name, traits=$traits, isAnonymous=$isAnonymous)"

    /** Collects the user's details. Every setter returns this, so calls chain. */
    public class Builder internal constructor() {
        private var id: String? = null
        private var name: String? = null
        private var traits: MutableMap<String, Any?>? = null
        private var anonymous: Boolean = false

        /** The user's identifier, which decides their bucket in a percentage rollout. */
        public fun id(id: String?): Builder = apply { this.id = id }

        /** The user's display name. */
        public fun name(name: String?): Builder = apply { this.name = name }

        /** Sets every trait at once, replacing any set so far. */
        public fun traits(traits: Map<String, Any?>?): Builder = apply {
            this.traits = traits?.let { LinkedHashMap(it) }
        }

        /** Adds one trait, keeping the rest. */
        public fun trait(key: String, value: Any?): Builder = apply {
            val traits = this.traits ?: LinkedHashMap<String, Any?>().also { this.traits = it }
            traits[key] = value
        }

        /**
         * Keeps the context out of the dashboard: it is evaluated but never persisted, and
         * telemetry reports neither the context nor its id.
         */
        public fun anonymous(anonymous: Boolean): Builder = apply { this.anonymous = anonymous }

        /**
         * Builds the context. Traits are copied, so the builder can be reused.
         *
         * @throws ConfigDirectorValidationException if a trait holds a value that is not
         *   JSON-shaped, and so could never match a targeting rule.
         */
        public fun build(): ConfigDirectorContext {
            val traits = this.traits
            traits?.let(::validateTraits)
            return ConfigDirectorContext(
                id = id,
                name = name,
                traits = traits?.let { Collections.unmodifiableMap(LinkedHashMap(it)) },
                isAnonymous = anonymous,
            )
        }
    }

    public companion object {
        private val EMPTY: ConfigDirectorContext = Builder().build()

        /** Starts an empty context. */
        @JvmStatic
        public fun builder(): Builder = Builder()

        /** A context carrying nothing, which matches only rules that need no user detail. */
        @JvmStatic
        public fun empty(): ConfigDirectorContext = EMPTY

        /**
         * Builds a context.
         *
         * ```kotlin
         * val context = ConfigDirectorContext.build {
         *     id("user-123")
         *     trait("plan", "pro")
         * }
         * ```
         */
        @JvmSynthetic
        public fun build(configure: Builder.() -> Unit): ConfigDirectorContext =
            Builder().apply(configure).build()
    }
}

private const val MAX_TRAIT_DEPTH = 32

private fun validateTraits(traits: Map<String, Any?>) {
    val enclosing = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    traits.forEach { (key, value) -> validateTrait(key, value, enclosing) }
}

private fun validateTrait(path: String, value: Any?, enclosing: MutableSet<Any>) {
    when (value) {
        null, is String, is Boolean -> Unit

        is Double -> requireFinite(path, value)

        is Float -> requireFinite(path, value.toDouble())

        is Number -> Unit

        is List<*> -> withinContainer(path, value, enclosing) {
            value.forEachIndexed { index, element ->
                validateTrait("$path[$index]", element, enclosing)
            }
        }

        is Map<*, *> -> withinContainer(path, value, enclosing) {
            value.forEach { (key, element) ->
                if (key !is String) {
                    throw ConfigDirectorValidationException(
                        "Invalid trait '$path'. A map inside a trait must be keyed by String, and " +
                            "this one is keyed by ${key?.javaClass?.name}.",
                    )
                }
                validateTrait("$path.$key", element, enclosing)
            }
        }

        else -> throw ConfigDirectorValidationException(
            "Invalid trait '$path' of type ${value.javaClass.name}. A trait value must be a " +
                "String, Number, Boolean, List, Map, or null; anything else has no form a " +
                "targeting rule could match.",
        )
    }
}

private fun requireFinite(path: String, value: Double) {
    if (value.isNaN() || value.isInfinite()) {
        throw ConfigDirectorValidationException(
            "Invalid trait '$path' with value $value. A trait number has to be finite: JSON has no " +
                "way to spell NaN or infinity, so the whole context would fail to send.",
        )
    }
}

private inline fun withinContainer(
    path: String,
    container: Any,
    enclosing: MutableSet<Any>,
    validateElements: () -> Unit,
) {
    if (!enclosing.add(container)) {
        throw ConfigDirectorValidationException(
            "Invalid trait '$path'. It contains itself, and a trait has to be a finite value.",
        )
    }
    if (enclosing.size > MAX_TRAIT_DEPTH) {
        throw ConfigDirectorValidationException(
            "Invalid trait '$path'. Traits nest at most $MAX_TRAIT_DEPTH levels deep.",
        )
    }
    validateElements()
    enclosing.remove(container)
}
