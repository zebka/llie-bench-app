package org.llie.mobilebenchmark.net

import android.content.SharedPreferences

/**
 * Persistent record of which model runs the server has acknowledged.
 *
 * This exists because a *failed* upload and a *never attempted* upload
 * are different things, and only the first one is visible to a failure
 * list. An upload queue that dies halfway through (process killed,
 * phone slept, network dropped) leaves the models it never reached
 * absent from every error log, so they can only be recovered by
 * comparing the runs on the device with the runs the server confirmed.
 *
 * Keys are `"<device label>|<model id>"`: the same install may be
 * re-labelled, and a run must be re-sent under the new label.
 */
class UploadLedger(private val prefs: SharedPreferences) {

    fun key(device: String, modelId: String): String = "$device|$modelId"

    /** True when the server has acknowledged this run for this label. */
    fun isConfirmed(device: String, modelId: String): Boolean =
        confirmedKeys().contains(key(device, modelId))

    /** Record a server acknowledgement. Committed synchronously: this is
     *  the only durable evidence that the data left the device, and a
     *  process kill right after the upload must not lose it. */
    fun confirm(device: String, modelId: String) {
        val keys = confirmedKeys().toMutableSet()
        keys.add(key(device, modelId))
        prefs.edit().putStringSet(KEY_CONFIRMED, keys).commit()
    }

    /** Drop a confirmation, e.g. after a re-run replaced the artifact. */
    fun forget(device: String, modelId: String) {
        val keys = confirmedKeys().toMutableSet()
        keys.remove(key(device, modelId))
        prefs.edit().putStringSet(KEY_CONFIRMED, keys).commit()
    }

    fun confirmedKeys(): Set<String> =
        prefs.getStringSet(KEY_CONFIRMED, emptySet()).orEmpty()

    companion object {
        const val PREFS = "llie"
        private const val KEY_CONFIRMED = "confirmed_uploads"
    }
}

/**
 * Which finished runs still need to reach the server.
 *
 * Pure so it can be unit tested without Android: a model qualifies when
 * it has a valid run on the device and the ledger has no confirmation
 * for it.
 */
object UploadRecovery {

    fun missing(
        device: String,
        stagedModels: List<String>,
        hasValidRun: (String) -> Boolean,
        isConfirmed: (String) -> Boolean,
    ): List<String> = stagedModels.filter { modelId ->
        hasValidRun(modelId) && !isConfirmed(modelId)
    }

    fun missing(
        ledger: UploadLedger,
        device: String,
        stagedModels: List<String>,
        hasValidRun: (String) -> Boolean,
    ): List<String> = missing(
        device = device,
        stagedModels = stagedModels,
        hasValidRun = hasValidRun,
        isConfirmed = { ledger.isConfirmed(device, it) },
    )
}
