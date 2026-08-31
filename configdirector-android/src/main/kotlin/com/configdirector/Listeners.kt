package com.configdirector

/** Told the evaluated value of one config, when it is watched and whenever it changes. */
public fun interface ConfigListener<T : Any> {
    /** Called on the main thread with the config's current value. */
    public fun onValue(value: T)
}

/** Told everything the client does. */
public fun interface ClientEventListener {
    /** Called on the main thread for each event the client publishes. */
    public fun onEvent(event: ClientEvent)
}

/** Told every config evaluation the client makes. */
public fun interface EvaluationListener {
    /**
     * Called on the main thread for each config read, including the reads a watch makes when it
     * re-evaluates.
     *
     * Do not read a config from here: the read publishes another evaluation, and this is called
     * again.
     */
    public fun onEvaluation(evaluation: ConfigEvaluation)
}
