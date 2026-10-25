package org.llie.mobilebenchmark.runner

/**
 * One process-wide stop latch for the current run.
 *
 * The benchmark loops read [stopRequested] between passes and between images
 * (a single volatile read, no clock call), so requesting a stop never changes
 * a measured sample. [BenchmarkExecutor] resets the latch when a run starts.
 */
object RunControl {
    @Volatile
    var stopRequested: Boolean = false
        private set

    fun requestStop() {
        stopRequested = true
    }

    fun reset() {
        stopRequested = false
    }
}
