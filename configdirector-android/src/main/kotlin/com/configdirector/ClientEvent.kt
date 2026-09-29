package com.configdirector

/** What prompted the client to (re)connect. */
public enum class ConnectReason(
    /** How the SDK names this reason in its logs. */
    public val description: String,
) {
    /** The client connected for the first time, from `ConfigDirectorClient.initialize`. */
    INITIALIZATION("initialization"),

    /** The client reconnected to re-evaluate configs against a new context. */
    CONTEXT_UPDATE("context update"),

    /** The client reconnected after its connection was resumed. */
    NETWORK_RESUME("network resume"),
}

/** Something the client did, handed to every [ClientEventListener]. */
public sealed class ClientEvent {

    /** The client became ready after connecting. */
    public class Ready internal constructor(
        /** What prompted the connection that made the client ready. */
        public val reason: ConnectReason,
    ) : ClientEvent() {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Ready && reason == other.reason)

        override fun hashCode(): Int = reason.hashCode()

        override fun toString(): String = "Ready(reason=$reason)"
    }

    /**
     * Config state was received from the server, carrying the keys it contained and the keys a full
     * update no longer contained.
     */
    public class ConfigsUpdated internal constructor(
        /** The keys the config state carried. */
        public val keys: List<String>,
        /**
         * The keys a full update no longer carried, so the client stopped serving them. Empty when
         * nothing was removed, and always empty for a delta update.
         */
        public val removedKeys: List<String>,
    ) : ClientEvent() {
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is ConfigsUpdated && keys == other.keys && removedKeys == other.removedKeys)

        override fun hashCode(): Int = 31 * keys.hashCode() + removedKeys.hashCode()

        override fun toString(): String = "ConfigsUpdated(keys=$keys, removedKeys=$removedKeys)"
    }

    /** A new context has taken effect. */
    public class ContextUpdated internal constructor(
        /** The context configs are evaluated against from now on. */
        public val context: ConfigDirectorContext?,
    ) : ClientEvent() {
        override fun equals(other: Any?): Boolean =
            this === other || (other is ContextUpdated && context == other.context)

        override fun hashCode(): Int = context.hashCode()

        override fun toString(): String = "ContextUpdated(context=$context)"
    }
}
