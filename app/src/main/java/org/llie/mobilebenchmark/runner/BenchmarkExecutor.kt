package org.llie.mobilebenchmark.runner

import android.content.Context
import android.os.Build
import org.json.JSONObject
import org.llie.mobilebenchmark.model.ImageCodec
import org.llie.mobilebenchmark.model.RgbImage
import org.llie.mobilebenchmark.model.SelectedRuntimeEngine
import org.llie.mobilebenchmark.storage.ResultWriter
import java.io.File
import java.security.MessageDigest

/**
 * One end-to-end on-device run: load model, warm up + measure per image,
 * save outputs, and write a schema-shaped run-result JSON. All timing uses
 * the in-process monotonic clock inside BenchmarkRunner; nothing here crosses
 * the ADB boundary while measuring.
 */
class BenchmarkExecutor(
    private val context: Context,
    private val config: BenchmarkConfig = BenchmarkConfig(),
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtimeNanos() },
) {

    data class RunOutcome(val success: Boolean, val resultFile: File, val detail: String)

    fun run(
        modelId: String,
        imageSet: String,
        backend: String = "cpu",
        imageNames: List<String>? = null,
    ): RunOutcome {
        val base = context.getExternalFilesDir(null)
            ?: return RunOutcome(false, File(context.filesDir, "$modelId-run.json"), "no external files dir")
        val resultsDir = File(base, "results")
        val suffix = if (backend == "cpu") "" else "-$backend"
        // Per-model+backend outputs dir so parallel/sequential models never overwrite.
        val outputsDirName = "outputs-$modelId$suffix"
        val outputsDir = File(resultsDir, outputsDirName)
        val resultFile = File(resultsDir, "$modelId$suffix-run.json")
        val statusFile = File(resultsDir, "$modelId$suffix.status")
        statusFile.parentFile?.mkdirs()
        statusFile.writeText("running")
        RunControl.reset()

        val thermalBefore = thermalStatus(context)
        val envBefore = EnvironmentRecorder.snapshot(context)
        val sampler = PssSampler()
        sampler.start()

        val engine = SelectedRuntimeEngine(backend)
        val runner = BenchmarkRunner(engine, config, clock)
        val results = mutableListOf<ImageRunResult>()
        val outputPaths = mutableMapOf<String, String>()
        var loadTimeNs = 0L
        var failureReason = ""

        try {
            val manifestFile = File(base, "manifests/$modelId.manifest.json")
            val modelFile = File(base, "models/$modelId.onnx")
            if (!manifestFile.isFile) throw IllegalStateException("manifest missing: ${manifestFile.name}")
            if (!modelFile.isFile) throw IllegalStateException("model missing: ${modelFile.name}")

            val loaded = runner.load(modelFile, manifestFile.readText(Charsets.UTF_8))
            loadTimeNs = loaded.loadTimeNs
            val manifest = loaded.manifest

            val imagesDir = File(base, "images/$imageSet")
            val imageFiles = resolveImageFiles(imagesDir, imageNames)
            if (imageFiles.isEmpty()) throw IllegalStateException("no images in $imageSet")

            RunMonitor.begin(
                modelId = modelId,
                backend = backend,
                imageSet = imageSet,
                imagesTotal = imageFiles.size,
                passTotal = config.measuredPasses,
                nowMs = android.os.SystemClock.elapsedRealtime(),
            )
            RunMonitor.markRunning()

            for (file in imageFiles) {
                if (RunControl.stopRequested) {
                    failureReason = "stopped by user"
                    break
                }
                RunMonitor.imageStarted(file.name)
                val startedAt = clock()
                val env = EnvironmentRecorder.snapshot(context)
                if (!file.isFile) {
                    results.add(
                        ImageRunResult(
                            imageId = file.name,
                            modelNs = emptyList(),
                            e2eNs = emptyList(),
                            output = null,
                            success = false,
                            failureReason = "input missing: ${file.name}",
                            startedAtNs = startedAt,
                            env = env,
                        )
                    )
                    RunMonitor.imageFinished()
                    continue
                }
                val bitmap = ImageCodec.decodeFile(file.absolutePath)
                if (bitmap.width != manifest.inputWidth || bitmap.height != manifest.inputHeight) {
                    results.add(
                        ImageRunResult(
                            imageId = file.name,
                            modelNs = emptyList(),
                            e2eNs = emptyList(),
                            output = null,
                            success = false,
                            failureReason = "shape mismatch: ${bitmap.width}x${bitmap.height} vs " +
                                "${manifest.inputWidth}x${manifest.inputHeight}",
                            startedAtNs = startedAt,
                            env = env,
                        )
                    )
                    bitmap.recycle()
                    RunMonitor.imageFinished()
                    continue
                }
                val rgb = ImageCodec.fromBitmap(bitmap)
                bitmap.recycle()
                runner.warmup(manifest, rgb)
                val measured = runner.measure(file.name, rgb) { index, elapsedMs ->
                    RunMonitor.passCompleted(index, elapsedMs)
                }.copy(
                    startedAtNs = startedAt,
                    env = env,
                )
                results.add(measured)
                if (measured.success) {
                    measured.output?.let { out ->
                        ImageCodec.savePng(out, File(outputsDir, file.name).absolutePath)
                        outputPaths[file.name] = "$outputsDirName/${file.name}"
                    }
                }
                RunMonitor.imageFinished()
            }
        } catch (t: Throwable) {
            failureReason = "${t.javaClass.simpleName}: ${t.message}"
            RunMonitor.complete(false, failureReason)
        } finally {
            engine.close()
        }

        val pssSamples = sampler.stop()
        RunMonitor.pssPeakMb = pssSamples.maxOrNull()?.toInt() ?: 0
        val thermalAfter = thermalStatus(context)
        val envAfter = EnvironmentRecorder.snapshot(context)
        val payload = ResultWriter.resultJson(
            modelId = modelId,
            artifactSha256 = sha256Of(File(base, "models/$modelId.onnx")),
            deviceModel = Build.MODEL,
            androidRelease = Build.VERSION.RELEASE ?: "unknown",
            androidSdk = Build.VERSION.SDK_INT,
            buildFingerprint = Build.FINGERPRINT,
            soc = socModelOrBoard(),
            runtimeName = "onnxruntime",
            runtimeVersion = ortVersion(),
            backend = backend,
            precision = "float32",
            warmupCount = config.warmupCount,
            measuredPasses = config.measuredPasses,
            protocolVersion = config.protocolVersion,
            loadTimeNs = loadTimeNs,
            results = results,
            outputPaths = outputPaths,
            pssSamplesMb = pssSamples,
            thermalBefore = thermalBefore,
            thermalAfter = thermalAfter,
            envBefore = envBefore,
            envAfter = envAfter,
            runFailureReason = failureReason,
        )
        ResultWriter.write(resultFile, payload)
        val success = payload.optBoolean("success", false)
        statusFile.writeText(if (success) "done" else "failed")
        RunMonitor.complete(success, failureReason)
        return RunOutcome(success, resultFile, payload.optString("failure_reason", ""))
    }

    companion object {
        /**
         * Build.SOC_MODEL exists only on API 29+. Reading it via a static
         * reference on older devices throws NoSuchFieldError before any
         * fallback can run, so resolve reflectively.
         */
        fun socModelOrBoard(): String {
            if (Build.VERSION.SDK_INT >= 29) {
                return runCatching { Build.SOC_MODEL }.getOrNull().orEmpty()
            }
            return runCatching {
                val field = Build::class.java.getField("SOC_MODEL")
                (field.get(null) as? String).orEmpty()
            }.getOrNull().orEmpty().ifEmpty { Build.BOARD }
        }

        /**
         * Resolve the ordered input list. An explicit host-supplied list wins

         * over directory listing, because scoped storage can filter
         * directory listings while direct file opens keep working.
         */
        fun resolveImageFiles(imagesDir: File, imageNames: List<String>?): List<File> {
            if (!imageNames.isNullOrEmpty()) return imageNames.map { File(imagesDir, it) }
            return imagesDir.listFiles { f -> f.isFile && f.extension.equals("png", true) }
                ?.sortedBy { it.name }
                .orEmpty()
        }

        fun sha256Of(file: File): String {
            if (!file.isFile) return "0".repeat(64)
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun thermalStatus(context: Context): String = EnvironmentRecorder.thermalStatus(context)

        fun ortVersion(): String = try {
            val envClass = Class.forName("ai.onnxruntime.OrtEnvironment")
            val getEnvironment = envClass.getMethod("getEnvironment")
            val env = getEnvironment.invoke(null)
            val versionMethod = envClass.methods.firstOrNull {
                it.name == "getVersion" && it.parameterCount == 0
            }
            versionMethod?.invoke(env)?.toString() ?: "unknown"
        } catch (t: Throwable) {
            "unknown"
        }
    }
}
