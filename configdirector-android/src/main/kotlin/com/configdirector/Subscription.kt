package com.configdirector

import java.io.Closeable

/** Undoes a watch or a listener registration. Closing twice is harmless. */
public fun interface Subscription : Closeable {
    /** Stops delivering to the listener this subscription was returned for. */
    override fun close()
}
