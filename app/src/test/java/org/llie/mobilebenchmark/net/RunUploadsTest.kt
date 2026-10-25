package org.llie.mobilebenchmark.net

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RunUploadsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun collectFindsTheFlatDeviceLayout() {
        val root = tmp.newFolder("results")
        File(root, "zero-dce-plus-run.json").writeText("{}")
        File(root, "zero-dce-plus.status").writeText("done")
        File(root, "outputs-zero-dce-plus").mkdirs()
        File(root, "outputs-zero-dce-plus/1.png").writeBytes(byteArrayOf(1))
        File(root, "outputs-zero-dce-plus/2.png").writeBytes(byteArrayOf(2))
        // another model's files must not leak in
        File(root, "pairlie-run.json").writeText("{}")

        val files = RunUploads.collect(root, "zero-dce-plus").map { it.name }

        assertEquals(listOf("zero-dce-plus-run.json", "zero-dce-plus.status", "1.png", "2.png"), files)
    }

    @Test
    fun collectIsEmptyWhenTheModelNeverRan() {
        val root = tmp.newFolder("results")
        assertEquals(emptyList<String>(), RunUploads.collect(root, "pairlie").map { it.name })
    }
}
