package org.llie.mobilebenchmark.net

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Staging bundle: the ONNX models, their manifest JSON files and the eval15
 * PNG images in one zip, served by the receiver at `GET /bundle`.
 *
 * Unpacking is defensive: absolute paths and `..` segments are skipped, and
 * every destination is re-checked against the target directory.
 */
object BundleArchive {

    /** Normalised relative path, or null for absolute/traversal/dot names. */
    fun safeEntryPath(name: String): String? {
        val normalized = name.replace('\\', '/')
        if (normalized.isEmpty() || normalized.startsWith("/")) return null
        val parts = normalized.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        return normalized
    }

    /** Extracts the bundle into [targetDir]; returns how many files were written. */
    fun unpack(input: InputStream, targetDir: File): Int {
        val root = targetDir.canonicalFile
        var written = 0
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val relative = safeEntryPath(entry.name)
                if (relative != null) {
                    val destination = File(targetDir, relative).canonicalFile
                    val inside = destination.path.startsWith(root.path + File.separator)
                    if (inside) {
                        if (entry.isDirectory) {
                            destination.mkdirs()
                        } else {
                            destination.parentFile?.mkdirs()
                            destination.outputStream().use { out -> zip.copyTo(out) }
                            written++
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        return written
    }
}

/** Downloads the staging bundle with optional progress percentages. */
class BundleDownloader(
    private val baseUrl: String,
    private val token: String,
) {
    data class Result(val ok: Boolean, val detail: String, val bytes: Long)

    fun fetch(destination: File, onProgress: (Int) -> Unit): Result {
        val connection = URL(baseUrl.trimEnd('/') + "/bundle").openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 120_000
            if (token.isNotBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $token")
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                Result(false, "HTTP $code", 0)
            } else {
                val total = connection.contentLengthLong
                var read = 0L
                var lastPercent = -1
                destination.parentFile?.mkdirs()
                destination.outputStream().use { out ->
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count <= 0) break
                            out.write(buffer, 0, count)
                            read += count
                            if (total > 0) {
                                val percent = ((read * 100) / total).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent)
                                }
                            }
                        }
                    }
                }
                Result(true, "downloaded", read)
            }
        } catch (t: Throwable) {
            Result(false, "${t.javaClass.simpleName}: ${t.message}", 0)
        } finally {
            connection.disconnect()
        }
    }
}
