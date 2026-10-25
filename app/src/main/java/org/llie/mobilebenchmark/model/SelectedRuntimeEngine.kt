package org.llie.mobilebenchmark.model

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.os.SystemClock
import java.io.File
import java.nio.FloatBuffer
import java.util.Collections

/**
 * The single runtime adapter selected by the Task 3 compatibility gate
 * (ONNX Runtime). The backend selects the execution provider: cpu
 * (CPUExecutionProvider), nnapi (NNAPI EP), xnnpack (XNNPACK EP), or the
 * schema labels gpu/npu which on this ORT build are only reachable via NNAPI.
 * Do not add a second unverified runtime adapter in Phase 1.
 *
 * Input packing and output unpacking are timed separately from `session.run`
 * and their buffers are reused: the measured "model latency" must not include
 * a fixed copy cost that dominates the smallest models, and per-inference
 * allocations would add GC noise to the same number.
 */
class SelectedRuntimeEngine(
    private val backend: String = "cpu",
    private val clock: () -> Long = { SystemClock.elapsedRealtimeNanos() },
) : ModelEngine {

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var options: OrtSession.SessionOptions? = null
    private var manifest: ModelManifest? = null
    private var chw = FloatArray(0)
    private var output = RgbImage.zeros(0, 0)

    override fun load(modelFile: File, manifest: ModelManifest) {
        close()
        val environment = OrtEnvironment.getEnvironment()
        val sessionOptions = OrtSession.SessionOptions().apply {
            when (backend) {
                "cpu" -> addCPU(true)
                "nnapi", "gpu", "npu" -> addNnapi()
                "xnnpack" -> addXnnpack(emptyMap())
                else -> throw IllegalArgumentException("unsupported backend: $backend")
            }
        }
        val newSession = environment.createSession(modelFile.absolutePath, sessionOptions)
        env = environment
        session = newSession
        options = sessionOptions
        this.manifest = manifest
        chw = FloatArray(3 * manifest.inputHeight * manifest.inputWidth)
        output = RgbImage.zeros(manifest.outputWidth, manifest.outputHeight)
    }

    override fun infer(input: RgbImage): RgbImage = inferTimed(input).image

    override fun inferTimed(input: RgbImage): TimedOutput {
        val m = checkNotNull(manifest) { "engine not loaded" }
        val session = checkNotNull(session)
        val environment = checkNotNull(env)

        require(input.width == m.inputWidth && input.height == m.inputHeight) {
            "input ${input.width}x${input.height} does not match manifest ${m.inputWidth}x${m.inputHeight}"
        }

        val h = m.inputHeight
        val w = m.inputWidth
        val pixels = h * w

        val prepareStart = clock()
        // RgbImage holds interleaved RGB (HWC); the graph expects NCHW.
        var src = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val r = input.data[src]
                val g = input.data[src + 1]
                val b = input.data[src + 2]
                src += 3
                val plane = y * w + x
                chw[plane] = r
                chw[pixels + plane] = g
                chw[2 * pixels + plane] = b
            }
        }
        val shape = longArrayOf(1, 3, h.toLong(), w.toLong())
        val tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(chw), shape)
        val prepareNs = clock() - prepareStart

        tensor.use {
            val runStart = clock()
            session.run(Collections.singletonMap("input", tensor)).use { results ->
                val runNs = clock() - runStart

                val unpackStart = clock()
                @Suppress("UNCHECKED_CAST")
                val value = results.get(0).value as Array<Array<Array<FloatArray>>>
                val oh = m.outputHeight
                val ow = m.outputWidth
                val out = output
                var i = 0
                for (y in 0 until oh) {
                    for (x in 0 until ow) {
                        out.data[i++] = value[0][0][y][x]
                        out.data[i++] = value[0][1][y][x]
                        out.data[i++] = value[0][2][y][x]
                    }
                }
                val unpackNs = clock() - unpackStart
                return TimedOutput(out, prepareNs, runNs, unpackNs)
            }
        }
    }

    override fun close() {
        session?.close()
        session = null
        options?.close()
        options = null
        env = null
        manifest = null
        chw = FloatArray(0)
        output = RgbImage.zeros(0, 0)
    }
}
