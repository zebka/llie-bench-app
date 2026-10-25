package org.llie.mobilebenchmark.runner

/**
 * Live progress state for the on-device monitor UI.
 *
 * The worker thread (BenchmarkExecutor / BenchmarkRunner) writes, the
 * main-thread ticker reads. Every field is @Volatile and independent, so the
 * UI is always allowed to be a fraction of a second behind; nothing here can
 * block or delay a measurement.
 *
 * The arithmetic helpers are top-level pure functions so they can be unit
 * tested without Android.
 */
object RunMonitor {
    const val PHASE_IDLE = "idle"
    const val PHASE_LOADING = "loading"
    const val PHASE_RUNNING = "running"
    const val PHASE_DONE = "done"
    const val PHASE_FAILED = "failed"

    @Volatile var phase: String = PHASE_IDLE
    @Volatile var modelId: String = ""
    @Volatile var backend: String = "cpu"
    @Volatile var imageSet: String = ""
    @Volatile var imagesTotal: Int = 0
    @Volatile var imagesDone: Int = 0
    @Volatile var currentImage: String = ""
    @Volatile var passIndex: Int = 0
    @Volatile var passTotal: Int = 0
    @Volatile var lastPassMs: Long = 0
    @Volatile var startMs: Long = 0
    @Volatile var failure: String = ""
    @Volatile var pssPeakMb: Int = 0

    private val passSamples = ArrayDeque<Long>()

    fun begin(
        modelId: String,
        backend: String,
        imageSet: String,
        imagesTotal: Int,
        passTotal: Int,
        nowMs: Long,
    ) {
        this.modelId = modelId
        this.backend = backend
        this.imageSet = imageSet
        this.imagesTotal = imagesTotal
        this.imagesDone = 0
        this.currentImage = ""
        this.passIndex = 0
        this.passTotal = passTotal
        this.lastPassMs = 0
        this.failure = ""
        this.pssPeakMb = 0
        this.startMs = nowMs
        synchronized(passSamples) { passSamples.clear() }
        this.phase = PHASE_LOADING
    }

    fun markRunning() {
        phase = PHASE_RUNNING
    }

    fun imageStarted(name: String) {
        currentImage = name
        passIndex = 0
    }

    fun passCompleted(index: Int, elapsedMs: Long) {
        passIndex = index
        lastPassMs = elapsedMs
        synchronized(passSamples) {
            passSamples.addLast(elapsedMs)
            while (passSamples.size > MAX_SAMPLES) passSamples.removeFirst()
        }
        phase = PHASE_RUNNING
    }

    fun imageFinished() {
        imagesDone += 1
        passIndex = 0
    }

    fun complete(success: Boolean, detail: String) {
        phase = if (success) PHASE_DONE else PHASE_FAILED
        failure = detail
    }

    /** Median of the recent measured passes (all of them, capped at 200). */
    fun medianPassMs(): Long? = synchronized(passSamples) { medianOf(passSamples.toList()) }

    private const val MAX_SAMPLES = 200
}

/** Median of a list of longs; null for an empty list. */
fun medianOf(values: List<Long>): Long? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
}

/** Percent of the whole image x pass matrix already completed, 0..100. */
fun progressPercent(imagesDone: Int, imagesTotal: Int, passIndex: Int, passTotal: Int): Int {
    if (imagesTotal <= 0 || passTotal <= 0) return 0
    val completed = imagesDone.toLong() * passTotal + passIndex
    val total = imagesTotal.toLong() * passTotal
    return ((completed * 100) / total).toInt().coerceIn(0, 100)
}

/** Total passes finished across all images, counting the current one's. */
fun passesCompleted(imagesDone: Int, passTotal: Int, passIndex: Int): Int =
    imagesDone * passTotal + passIndex

/**
 * Remaining time at the mean pass time so far. Null until one pass is done
 * (there is nothing honest to extrapolate from yet).
 */
fun etaMs(
    imagesDone: Int,
    imagesTotal: Int,
    passTotal: Int,
    passIndex: Int,
    elapsedMs: Long,
): Long? {
    val completed = passesCompleted(imagesDone, passTotal, passIndex)
    if (completed <= 0 || elapsedMs <= 0) return null
    val remaining = imagesTotal * passTotal - completed
    if (remaining <= 0) return 0L
    return (elapsedMs / completed) * remaining
}

/** mm:ss for anything under an hour, h:mm:ss above it. */
fun formatDurationMs(ms: Long): String {
    if (ms < 0) return "--:--"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

/** "1805/1805/2000/2000/1700/1700" for the per-core line. */
fun formatFreqsMhz(freqs: List<Int>): String =
    if (freqs.isEmpty()) "n/a" else freqs.joinToString("/")
