package org.llie.mobilebenchmark.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class StagingBundleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun safeEntryPathRejectsAbsoluteAndTraversalNames() {
        assertNull(BundleArchive.safeEntryPath(""))
        assertNull(BundleArchive.safeEntryPath("/etc/passwd"))
        assertNull(BundleArchive.safeEntryPath("../escape.txt"))
        assertNull(BundleArchive.safeEntryPath("a/../../b"))
        assertNull(BundleArchive.safeEntryPath("a//b"))
        assertEquals("models/zero-dce.onnx", BundleArchive.safeEntryPath("models/zero-dce.onnx"))
        assertEquals("models/x.onnx", BundleArchive.safeEntryPath("models\\x.onnx"))
    }

    @Test
    fun unpackWritesRegularEntriesAndSkipsTraversal() {
        val zip = File(tmp.root, "bundle.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("models/x.onnx"))
            out.write("weights".toByteArray())
            out.closeEntry()
            out.putNextEntry(ZipEntry("images/eval15/1.png"))
            out.write(byteArrayOf(1, 2, 3))
            out.closeEntry()
            out.putNextEntry(ZipEntry("../escape.txt"))
            out.write("bad".toByteArray())
            out.closeEntry()
        }
        val target = tmp.newFolder("staged")

        val written = zip.inputStream().use { BundleArchive.unpack(it, target) }

        assertEquals(2, written)
        assertEquals("weights", File(target, "models/x.onnx").readText())
        assertTrue(File(target, "images/eval15/1.png").isFile)
        assertFalse(File(tmp.root, "escape.txt").exists())
    }
}
