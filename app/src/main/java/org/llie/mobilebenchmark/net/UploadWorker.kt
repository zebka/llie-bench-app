package org.llie.mobilebenchmark.net

import android.content.Context
import android.util.Log
import org.llie.mobilebenchmark.BuildConfig
import java.io.File

/**
 * Uploads finished runs one model at a time, as soon as each one is
 * finished, and remembers what the server confirmed.
 *
 * Why this is not just a call to [ReceiverUploader] at the end of the
 * batch: the earlier design uploaded every model after the whole queue
 * finished, in one shot, from a plain background thread. If the process
 * died in the middle of that (the user swiped the app away, the phone
 * slept, the network dropped), the models that had not been reached yet
 * were lost -- and because a *failed* upload and a *never attempted*
 * upload look identical from the outside, nothing was left to retry.
 * Uploading per model as it completes, and recording every server
 * acknowledgement in [UploadLedger], makes an interrupted batch
 * recoverable: the next launch compares the runs on the device with the
 * ledger and re-sends only what is missing.
 */
class UploadWorker(
    private val context: Context,
    private val ledger: UploadLedger,
    private val uploader: ReceiverUploader = ReceiverUploader(
        BuildConfig.SERVER_URL,
        BuildConfig.SERVER_TOKEN,
    ),
) {

    /** Uploads one model's run. Returns true when the server accepted it. */
    fun upload(device: String, modelId: String, resultsRoot: File): Boolean {
        val files = RunUploads.collect(resultsRoot, modelId)
        if (files.isEmpty()) {
            Log.w(TAG, "no files for $modelId under $resultsRoot")
            return false
        }
        val result = uploader.upload(device, modelId, files)
        if (result.ok) {
            ledger.confirm(device, modelId)
        } else {
            Log.w(TAG, "upload failed $modelId: ${result.detail}")
        }
        return result.ok
    }

    /**
     * Uploads every staged model that has a valid run on the device but
     * no server confirmation. Safe to call repeatedly: confirmed models
     * are skipped, so a re-launch after a crash resumes exactly where
     * the previous attempt stopped.
     *
     * @return the model ids that were sent successfully in this pass.
     */
    fun uploadMissing(
        device: String,
        modelsDir: File,
        resultsRoot: File,
        hasValidRun: (String) -> Boolean,
    ): List<String> {
        val staged = modelsDir.listFiles { f -> f.isFile && f.extension == "onnx" }
            .orEmpty()
            .map { it.nameWithoutExtension }
            .sorted()
        val missing = UploadRecovery.missing(
            ledger = ledger,
            device = device,
            stagedModels = staged,
            hasValidRun = hasValidRun,
        )
        if (missing.isEmpty()) return emptyList()
        Log.i(TAG, "uploading ${missing.size} unconfirmed run(s): $missing")
        return missing.filter { upload(device, it, resultsRoot) }
    }

    private companion object {
        const val TAG = "LLIEUpload"
    }
}
