package org.llie.mobilebenchmark.model

import java.io.File

/** Simple RGB image buffer passed through the engine boundary. */
data class RgbImage(val width: Int, val height: Int, val data: FloatArray) {
    init {
        require(data.size == width * height * 3) { "expected ${width * height * 3} floats, got ${data.size}" }
    }

    companion object {
        fun zeros(width: Int, height: Int): RgbImage =
            RgbImage(width, height, FloatArray(width * height * 3))
    }
}

/**
 * One inference split into the phases the protocol reports separately:
 * packing the input, the runtime call itself, and unpacking the output.
 * `image` is owned by the engine and is overwritten by the next call.
 */
data class TimedOutput(
    val image: RgbImage,
    val prepareNs: Long,
    val runNs: Long,
    val unpackNs: Long,
)

/** Runtime-agnostic engine boundary used by the benchmark runner. */
interface ModelEngine {
    fun load(modelFile: File, manifest: ModelManifest)

    fun infer(input: RgbImage): RgbImage

    fun inferTimed(input: RgbImage): TimedOutput

    fun close()
}