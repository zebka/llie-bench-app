package org.llie.mobilebenchmark.runner

import java.util.Collections

/**
 * Process-wide "test all" state.
 *
 * The queue runs on a background thread and can outlive the activity (screen
 * rotation, window resize, fold/unfold). Keeping the flag and the per-model
 * statuses here means a recreated activity re-attaches to the running queue
 * instead of offering a second one.
 */
object TestAllState {
    @Volatile
    var running: Boolean = false
        private set

    private val states = Collections.synchronizedMap(LinkedHashMap<String, String>())

    fun begin() {
        states.clear()
        running = true
    }

    fun setState(modelId: String, state: String) {
        states[modelId] = state
    }

    fun stateOf(modelId: String): String? = states[modelId]

    fun snapshot(): Map<String, String> = synchronized(states) { LinkedHashMap(states) }

    fun finish() {
        running = false
    }

    fun reset() {
        states.clear()
        running = false
    }
}
