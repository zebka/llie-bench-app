package org.llie.mobilebenchmark.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultipartTest {

    @Test
    fun bodyCarriesTheMetaFieldFirst() {
        val body = Multipart
            .build("B", "{\"device\":\"d\"}", listOf(UploadPart(filename = "run.json", bytes = "{}".toByteArray())))
            .toString(Charsets.UTF_8)
        assertTrue(
            body.startsWith(
                "--B\r\nContent-Disposition: form-data; name=\"meta\"\r\n" +
                    "Content-Type: application/json\r\n\r\n{\"device\":\"d\"}\r\n",
            ),
        )
    }

    @Test
    fun bodyCarriesEveryFilePartAndTheClosingBoundary() {
        val body = Multipart
            .build(
                "B",
                "{}",
                listOf(
                    UploadPart(filename = "run.json", bytes = "run".toByteArray()),
                    UploadPart(filename = "1.png", bytes = byteArrayOf(1, 2, 3)),
                ),
            )
            .toString(Charsets.UTF_8)
        assertTrue(body.contains("name=\"files\"; filename=\"run.json\""))
        assertTrue(body.contains("name=\"files\"; filename=\"1.png\""))
        assertTrue(body.endsWith("--B--\r\n"))
    }

    @Test
    fun binaryBytesSurviveTheEncoding() {
        val payload = byteArrayOf(0, 1, 2, 127, -1, -2, 10, 13)
        val body = Multipart.build("B", "{}", listOf(UploadPart(filename = "x.bin", bytes = payload)))
        val marker = "filename=\"x.bin\"\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray()
        val start = body.indices.first { i ->
            i + marker.size <= body.size && (0 until marker.size).all { j -> body[i + j] == marker[j] }
        } + marker.size
        assertEquals(payload.toList(), body.slice(start until start + payload.size))
    }
}
