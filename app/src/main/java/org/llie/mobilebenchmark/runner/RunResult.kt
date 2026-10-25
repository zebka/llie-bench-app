package org.llie.mobilebenchmark.runner

import org.llie.mobilebenchmark.model.RgbImage

/**
 * One image's measurement record; success/failure is explicit, never silent.
 * `prepareNs`/`runNs`/`unpackNs` are the phase split of the same passes that
 * produced `modelNs`, so old runs and new runs stay comparable.
 */
data class ImageRunResult(
    val imageId: String,
    val modelNs: List<Long>,
    val e2eNs: List<Long>,
    val output: RgbImage?,
    val success: Boolean,
    val failureReason: String,
    val prepareNs: List<Long> = emptyList(),
    val runNs: List<Long> = emptyList(),
    val unpackNs: List<Long> = emptyList(),
    val startedAtNs: Long = 0,
    val env: EnvSnapshot? = null,
)
