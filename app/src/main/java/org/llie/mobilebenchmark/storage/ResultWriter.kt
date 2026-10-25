package org.llie.mobilebenchmark.storage

import org.json.JSONArray
import org.json.JSONObject
import org.llie.mobilebenchmark.runner.EnvSnapshot
import org.llie.mobilebenchmark.runner.ImageRunResult
import java.io.File

/** Writes the stable, schema-shaped run-result JSON for one model run. */
object ResultWriter {

    fun resultJson(
        modelId: String,
        artifactSha256: String,
        deviceModel: String,
        androidRelease: String,
        androidSdk: Int,
        buildFingerprint: String,
        soc: String,
        runtimeName: String,
        runtimeVersion: String,
        backend: String,
        precision: String,
        warmupCount: Int,
        measuredPasses: Int,
        protocolVersion: Int,
        loadTimeNs: Long,
        results: List<ImageRunResult>,
        outputPaths: Map<String, String>,
        pssSamplesMb: List<Double>,
        thermalBefore: String,
        thermalAfter: String,
        envBefore: EnvSnapshot? = null,
        envAfter: EnvSnapshot? = null,
        runFailureReason: String = "",
    ): JSONObject {
        val images = JSONArray()
        val imageIds = JSONArray()
        val paths = JSONArray()
        for (r in results) {
            imageIds.put(r.imageId)
            if (r.success) {
                outputPaths[r.imageId]?.let { paths.put(it) }
            }
            val image = JSONObject()
                .put("image_id", r.imageId)
                .put("model_ns", JSONArray(r.modelNs))
                .put("e2e_ns", JSONArray(r.e2eNs))
            if (r.prepareNs.isNotEmpty()) image.put("prepare_ns", JSONArray(r.prepareNs))
            if (r.runNs.isNotEmpty()) image.put("run_ns", JSONArray(r.runNs))
            if (r.unpackNs.isNotEmpty()) image.put("unpack_ns", JSONArray(r.unpackNs))
            if (r.startedAtNs > 0) image.put("started_at_ns", r.startedAtNs)
            r.env?.let { env ->
                env.batteryTempC?.let { image.put("battery_temp_c", it) }
                image.put("screen_on", env.screenOn)
                image.put("cpu_freqs_mhz", JSONArray(env.cpuFreqsMhz))
            }
            images.put(image)
        }
        val firstFailedReason = results.firstOrNull { !it.success }?.failureReason ?: ""
        val success = results.isNotEmpty() && results.all { it.success }
        val failureReason = firstFailedReason.ifEmpty { runFailureReason }.ifEmpty { "run failed" }
        val peakMb = pssSamplesMb.maxOrNull() ?: 0.0

        val payload = JSONObject()
            .put("model_id", modelId)
            .put("artifact_sha256", artifactSha256)
            .put(
                "device",
                JSONObject()
                    .put("model", deviceModel)
                    .put("android_release", androidRelease)
                    .put("android_sdk", androidSdk)
                    .put("build_fingerprint", buildFingerprint)
                    .put("soc", soc)
            )
            .put("runtime", JSONObject().put("name", runtimeName).put("version", runtimeVersion))
            .put("backend", backend)
            .put("precision", precision)
            .put("image_ids", imageIds)
            .put("warmup_count", warmupCount)
            .put("measured_passes", measuredPasses)
            .put("protocol_version", protocolVersion)
            .put("load_time_ns", loadTimeNs)
            .put("images", images)
            .put(
                "pss",
                JSONObject()
                    .put("samples_mb", JSONArray(pssSamplesMb))
                    .put("peak_mb", peakMb)
            )
            .put(
                "thermal",
                JSONObject().put("before", thermalBefore).put("after", thermalAfter)
            )

        val before = envBefore?.toJson()
        val after = envAfter?.toJson()
        if (before != null && after != null) {
            payload.put("environment", JSONObject().put("before", before).put("after", after))
        }

        return payload
            .put("output_paths", paths)
            .put("success", success)
            .apply { if (!success) put("failure_reason", failureReason) }
    }

    fun write(file: File, payload: JSONObject) {
        file.parentFile?.mkdirs()
        file.writeText(payload.toString(2), Charsets.UTF_8)
    }
}
