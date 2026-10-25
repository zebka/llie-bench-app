package org.llie.mobilebenchmark.runner

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.io.File

/**
 * Orchestrates an on-device "test all" pass over the staged models.
 *
 * Order is fixed heavy -> light so the thermally expensive models run on the
 * coolest device state; the protocol itself is unchanged (15 eval15 images,
 * 10 measured passes, CPU). Between models the runner waits for the battery
 * temperature to fall back to [coolTargetC] so the next model starts from a
 * comparable machine, exactly like the host-driven flow does.
 */
class TestAllRunner(
    private val context: Context,
    private val coolTargetC: Double = 38.0,
    private val coolTimeoutMs: Long = 15 * 60 * 1000L,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {

    data class ModelOutcome(val modelId: String, val success: Boolean, val detail: String)

    fun runAll(models: List<String>, onState: (modelId: String, state: String) -> Unit): List<ModelOutcome> {
        RunControl.reset()
        val outcomes = mutableListOf<ModelOutcome>()
        for (model in models) {
            if (RunControl.stopRequested) break
            onState(model, "cooling")
            waitForTemperature()
            if (RunControl.stopRequested) break
            onState(model, "running")
            val outcome = BenchmarkExecutor(context).run(model, IMAGE_SET, BACKEND, null)
            val state = when {
                RunControl.stopRequested -> "stopped"
                outcome.success -> "done"
                else -> "failed"
            }
            onState(model, state)
            outcomes += ModelOutcome(model, outcome.success, outcome.detail)
        }
        return outcomes
    }

    /** Waits until the battery is at or below the target, or the timeout ends. */
    fun waitForTemperature(): Double? {
        val deadline = clock() + coolTimeoutMs
        while (clock() < deadline && !RunControl.stopRequested) {
            val temp = EnvironmentRecorder.snapshot(context).batteryTempC
            if (temp == null || temp <= coolTargetC) return temp
            Thread.sleep(COOL_POLL_MS)
        }
        return EnvironmentRecorder.snapshot(context).batteryTempC
    }

    companion object {
        const val IMAGE_SET = "eval15"
        const val BACKEND = "cpu"
        private const val COOL_POLL_MS = 10_000L

        /** Heavy -> light; unknown staged models follow alphabetically. */
        val ORDER = listOf(
            "retinexformer-lolv1",
            "lyt-net",
            "pairlie",
            "zero-dce-plus",
            "zero-dce",
            "mobileie-6ch",
            "ruas-lol",
            "sci-medium",
        )

        fun discoverModels(modelsDir: File): List<String> {
            val staged = modelsDir.listFiles { f -> f.isFile && f.extension == "onnx" }
                .orEmpty()
                .map { it.nameWithoutExtension }
            return staged.sortedWith(compareBy({ ORDER.indexOf(it).takeIf { i -> i >= 0 } ?: ORDER.size }, { it }))
        }

        /**
         * A run is reusable when it completed and speaks protocol v2.
         * `resultsRoot` is the flat on-device `results/` directory
         * (`<model>-run.json`, `outputs-<model>/`).
         */
        fun hasValidRun(resultsRoot: File, modelId: String): Boolean {
            val run = File(resultsRoot, "$modelId-run.json")
            if (!run.isFile) return false
            return try {
                val json = JSONObject(run.readText())
                json.optBoolean("success", false) && json.optInt("protocol_version", 0) == 2
            } catch (t: Throwable) {
                false
            }
        }

        /** Models still worth running: everything, or only those without a valid v2 run. */
        fun pendingModels(all: List<String>, skipValid: Boolean, resultsRoot: File): List<String> =
            if (!skipValid) all else all.filterNot { hasValidRun(resultsRoot, it) }
    }
}
