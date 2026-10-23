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

    /**
     * Never connects to ConfigDirector and sends nothing: the client serves
     * `ConnectionOptions.values` to every context, or every config's in-code default value when
     * there are none. It needs no client SDK key.
     */
    OFFLINE,
}
