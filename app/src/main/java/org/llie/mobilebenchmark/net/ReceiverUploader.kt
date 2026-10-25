package org.llie.mobilebenchmark.net

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One file part of a multipart/form-data body. */
data class UploadPart(
    val field: String = "files",
    val filename: String,
    val bytes: ByteArray,
)

/** Multipart encoding kept as a pure function so it can be unit tested. */
object Multipart {
    fun build(boundary: String, metaJson: String, parts: List<UploadPart>): ByteArray {
        val out = ByteArrayOutputStream()
        fun text(value: String) = out.write(value.toByteArray(Charsets.UTF_8))
        text("--$boundary\r\n")
        text("Content-Disposition: form-data; name=\"meta\"\r\n")
        text("Content-Type: application/json\r\n\r\n")
        text(metaJson)
        text("\r\n")
        for (part in parts) {
            text("--$boundary\r\n")
            text(
                "Content-Disposition: form-data; name=\"${part.field}\"; " +
                    "filename=\"${part.filename}\"\r\n",
            )
            text("Content-Type: application/octet-stream\r\n\r\n")
            out.write(part.bytes)
            text("\r\n")
        }
        text("--$boundary--\r\n")
        return out.toByteArray()
    }
}

/** Posts one finished run (run.json + outputs) to the configured receiver. */
class ReceiverUploader(
    private val baseUrl: String,
    private val token: String,
) {
    data class Result(val ok: Boolean, val detail: String)

    fun upload(device: String, model: String, files: List<File>): Result {
        val existing = files.filter { it.isFile }
        if (existing.isEmpty()) return Result(false, "nothing to upload")
        val runId = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val meta = JSONObject()
            .put("device", device)
            .put("model", model)
            .put("run_id", runId)
            .put("source", "android-app")
            .toString()
        val boundary = "llie" + System.nanoTime().toString(16)
        val parts = existing.map { UploadPart(filename = it.name, bytes = it.readBytes()) }
        val body = Multipart.build(boundary, meta, parts)
        val connection = URL(baseUrl.trimEnd('/') + "/upload").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            if (token.isNotBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $token")
            }
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty().take(300)
            Result(code in 200..299, "$code $text")
        } catch (t: Throwable) {
            Result(false, "${t.javaClass.simpleName}: ${t.message}")
        } finally {
            connection.disconnect()
        }
    }
}

/** Files that belong to one finished model run on the device. */
object RunUploads {
    /**
     * The on-device layout is flat: `results/<model>-run.json`,
     * `results/<model>.status` and the PNG files under
     * `results/outputs-<model>/` (BenchmarkExecutor writes it this way).
     */
    fun collect(resultsRoot: File, modelId: String): List<File> {
        val files = mutableListOf<File>()
        for (name in listOf("$modelId-run.json", "$modelId.status", "$modelId.metrics.csv")) {
            File(resultsRoot, name).takeIf { it.isFile }?.let { files += it }
        }
        File(resultsRoot, "outputs-$modelId")
            .listFiles { f -> f.isFile }
            ?.sortedBy { it.name }
            ?.let { files += it }
        return files
    }
}
