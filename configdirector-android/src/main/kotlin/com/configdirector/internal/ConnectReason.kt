package com.configdirector.internal

/** What prompted the client to (re)connect. */
internal enum class ConnectReason(val description: String) {
    INITIALIZATION("initialization"),
    CONTEXT_UPDATE("context update"),
}
