package com.configdirector

/** Told that an operation the client was asked to perform has finished. */
public fun interface CompletionCallback {
    /** Called on the main thread once the operation finishes. */
    public fun onComplete()
}
