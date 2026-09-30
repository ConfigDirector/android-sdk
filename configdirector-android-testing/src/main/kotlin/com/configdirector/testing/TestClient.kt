package com.configdirector.testing

import com.configdirector.ConfigDirectorClient
import com.configdirector.ConfigDirectorContext
import com.configdirector.ConfigDirectorTestingApi
import com.configdirector.internal.testing.InMemoryConnection

/**
 * The controls of a test client made by [createTestClient], and the [client] they drive.
 *
 * Every value the test client serves is served to every context. The initialization controls act
 * only on `initialize` and the context update controls only on `updateContext`; `resumeNetwork` is
 * never held or failed. After `close`, every control is a silent no-op.
 */
@OptIn(ConfigDirectorTestingApi::class)
public class TestClient internal constructor(private val connection: InMemoryConnection) {

    /**
     * The client under test. It is a [ConfigDirectorClient] like any other, so it goes anywhere
     * production code accepts one. Close it when the test is done, as production code would.
     */
    public val client: ConfigDirectorClient
        get() = connection.client

    /**
     * The context of every `initialize` and `updateContext` call, in call order. `initialize`
     * without a context records an empty context. `resumeNetwork` is not recorded.
     */
    public val contextUpdates: List<ConfigDirectorContext>
        get() = connection.contextUpdates

    /**
     * Stores [value] under [key] and, once the client is connected, delivers an update carrying
     * only [key]: watches of [key] are called, `ConfigsUpdated` lists it, and reads see it. The
     * config type follows from the value's type: a `Boolean`; an `Int` or `Long` (an integer
     * config); a `Float` or `Double` (a float config); a `String`; or a `Map` with `String` keys or
     * a `List` (a JSON config). A `String` is always a string config, so JSON text meant as a JSON
     * config is given as a `Map` or a `List`.
     *
     * @throws com.configdirector.ConfigDirectorValidationException if [key] is blank, the value is
     *   of another type or not finite, or a JSON document holds a value of another type or a
     *   non-`String` key; nothing changes
     */
    public fun setValue(key: String, value: Any) {
        connection.setValue(key, value)
    }

    /**
     * Removes the value under [key] and, once the client is connected, delivers a full update
     * without it: reads of [key] return the in-code default value with the `CONFIG_STATE_MISSING`
     * reason, watches of [key] are handed that default, and `ConfigsUpdated` lists [key] in its
     * `removedKeys`.
     */
    public fun removeValue(key: String) {
        connection.removeValue(key)
    }

    /**
     * Replaces every stored value with [values], disarms any armed hold or failure, and, once the
     * client is connected, delivers a full update. Use it to reset a test client shared across
     * tests. See [setValue] for the accepted values.
     *
     * @throws com.configdirector.ConfigDirectorValidationException if a value cannot be encoded;
     *   nothing changes
     */
    public fun replaceValues(values: Map<String, Any>) {
        connection.replaceValues(values)
    }

    /**
     * Makes the next `initialize` wait, not ready, until [completeInitialization] or
     * [failInitialization], or until the client's connection timeout elapses.
     */
    public fun holdInitialization() {
        connection.holdInitialization()
    }

    /**
     * Delivers the stored values to a held `initialize`, so the client is ready when this returns,
     * and then lets `initialize` complete. Called while a hold is armed but no `initialize` has
     * picked it up, it disarms the hold.
     */
    public fun completeInitialization() {
        connection.completeInitialization()
    }

    /**
     * Fails a held `initialize` the way an invalid SDK key does: it completes promptly, the client
     * is not ready, and the client logs the error. Called while no `initialize` is held, it arms
     * the next one to fail.
     */
    public fun failInitialization() {
        connection.failInitialization()
    }

    /** As [holdInitialization], for the next `updateContext`. */
    public fun holdContextUpdate() {
        connection.holdContextUpdate()
    }

    /** As [completeInitialization], for a held `updateContext`. */
    public fun completeContextUpdate() {
        connection.completeContextUpdate()
    }

    /** As [failInitialization], for a held `updateContext`. */
    public fun failContextUpdate() {
        connection.failContextUpdate()
    }
}
