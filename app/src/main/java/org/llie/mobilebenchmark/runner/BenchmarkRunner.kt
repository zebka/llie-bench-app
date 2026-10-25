package org.llie.mobilebenchmark.runner

import android.os.SystemClock
import org.llie.mobilebenchmark.model.ModelEngine
import org.llie.mobilebenchmark.model.ModelManifest
import org.llie.mobilebenchmark.model.RgbImage
import java.io.File

/**
 * Owns the Phase 1 run protocol: batch=1, warm-up passes, then
 * `config.measuredPasses` measured passes per image with an in-process
 * monotonic clock (`SystemClock.elapsedRealtimeNanos`).
 *
 * The wall-clock sample stays in `modelNs` so old and new runs are comparable,
 * while the engine's phase split is recorded next to it. The gap between the
 * two is harness overhead, not model time.
 */
class BenchmarkRunner(
    private val engine: ModelEngine,
    private val config: BenchmarkConfig = BenchmarkConfig(),
    private val clock: () -> Long = { SystemClock.elapsedRealtimeNanos() },
) {

    data class Loaded(val manifest: ModelManifest, val loadTimeNs: Long)

    fun load(modelFile: File, manifestText: String): Loaded {
        val manifest = ModelManifest.fromJson(manifestText)
        val start = clock()
        engine.load(modelFile, manifest)
        val loadTime = clock() - start
        return Loaded(manifest, loadTime)
    }

    fun warmup(manifest: ModelManifest, image: RgbImage) {
        repeat(config.warmupCount) { engine.infer(image) }
    }

    fun measure(
        imageId: String,
        image: RgbImage,
        onPass: ((index: Int, elapsedMs: Long) -> Unit)? = null,
    ): ImageRunResult {
        val modelSamples = ArrayList<Long>(config.measuredPasses)
        val e2eSamples = ArrayList<Long>(config.measuredPasses)
        val prepareSamples = ArrayList<Long>(config.measuredPasses)
        val runSamples = ArrayList<Long>(config.measuredPasses)
        val unpackSamples = ArrayList<Long>(config.measuredPasses)
        var lastOutput: RgbImage? = null

        for (pass in 0 until config.measuredPasses) {
            if (RunControl.stopRequested) {
                return ImageRunResult(
                    imageId = imageId,
                    modelNs = modelSamples,
                    e2eNs = e2eSamples,
                    output = lastOutput,
                    success = false,
                    failureReason = "stopped by user",
                    prepareNs = prepareSamples,
                    runNs = runSamples,
                    unpackNs = unpackSamples,
                )
            }
            val e2eStart = clock()
            val modelStart = clock()
            val timed = try {
                engine.inferTimed(image)
            } catch (t: Throwable) {
                return ImageRunResult(
                    imageId = imageId,
                    modelNs = modelSamples,
                    e2eNs = e2eSamples,
                    output = null,
                    success = false,
                    failureReason = "${t.javaClass.simpleName}: ${t.message}",
                    prepareNs = prepareSamples,
                    runNs = runSamples,
                    unpackNs = unpackSamples,
                )
            }
            val modelEnd = clock()
            val e2eEnd = clock()
            modelSamples.add(modelEnd - modelStart)
            e2eSamples.add(e2eEnd - e2eStart)
            prepareSamples.add(timed.prepareNs)
            runSamples.add(timed.runNs)
            unpackSamples.add(timed.unpackNs)
            lastOutput = timed.image
            // Monitor feed only: derived from the boundaries already taken,
            // so the measured samples and their clock-call count are unchanged.
            onPass?.invoke(pass + 1, (e2eEnd - e2eStart) / 1_000_000L)
        }
        return ImageRunResult(
            imageId = imageId,
            modelNs = modelSamples,
            e2eNs = e2eSamples,
            output = lastOutput,
            success = true,
            failureReason = "",
            prepareNs = prepareSamples,
            runNs = runSamples,
            unpackNs = unpackSamples,
        )
    }
}
