package com.configdirector

/** How the client keeps its config state current. */
public enum class ConnectionMode {
    /**
     * Keeps a connection open and receives updates as soon as config state changes in the
     * ConfigDirector dashboard.
     */
    STREAMING,

    /** Fetches config state during initialization and then on a fixed interval. */
    POLLING,

    /** Fetches config state during initialization and on context updates only. */
    ONE_TIME,
}
