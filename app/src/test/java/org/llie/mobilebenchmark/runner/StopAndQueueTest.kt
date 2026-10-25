package org.llie.mobilebenchmark.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.llie.mobilebenchmark.model.ModelEngine
import org.llie.mobilebenchmark.model.ModelManifest
import org.llie.mobilebenchmark.model.RgbImage
import org.llie.mobilebenchmark.model.TimedOutput
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class StopAndQueueTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val image = RgbImage.zeros(2, 2)

    private class FakeEngine(private val output: TimedOutput) : ModelEngine {
        override fun load(modelFile: File, manifest: ModelManifest) = Unit

        override fun infer(input: RgbImage): RgbImage = output.image

        override fun inferTimed(input: RgbImage): TimedOutput = output

        override fun close() = Unit
    }

    @Test
    fun stopLatchIsProcessWide() {
        RunControl.reset()
        assertFalse(RunControl.stopRequested)
        RunControl.requestStop()
        assertTrue(RunControl.stopRequested)
        RunControl.reset()
        assertFalse(RunControl.stopRequested)
    }

    @Test
    fun measureStopsBeforeTheNextPassWhenRequested() {
        val runner = BenchmarkRunner(
            FakeEngine(TimedOutput(image, prepareNs = 1, runNs = 1, unpackNs = 1)),
            BenchmarkConfig(warmupCount = 0, measuredPasses = 3),
            clock = { throw AssertionError("timing must not run after a stop") },
        )
        RunControl.requestStop()
        try {
            val result = runner.measure("1.png", image)
            assertFalse(result.success)
            assertEquals("stopped by user", result.failureReason)
            assertTrue(result.modelNs.isEmpty())
        } finally {
            RunControl.reset()
        }
    }

    @Test
    fun discoverModelsOrdersHeavyToLightThenAlphabetically() {
        val dir = tmp.newFolder("models")
        for (name in listOf("zero-dce", "retinexformer-lolv1", "unknown-model", "sci-medium")) {
            File(dir, "$name.onnx").writeText("x")
        }
        assertEquals(
            listOf("retinexformer-lolv1", "zero-dce", "sci-medium", "unknown-model"),
            TestAllRunner.discoverModels(dir),
        )
    }

    @Test
    fun pendingModelsSkipsValidV2RunsOnly() {
        val root = tmp.newFolder("results")
        File(root, "retinexformer-lolv1-run.json")
            .writeText("""{"success": true, "protocol_version": 2}""")
        File(root, "pairlie-run.json")
            .writeText("""{"success": true, "protocol_version": 1}""")
        File(root, "zero-dce-plus-run.json")
            .writeText("""{"success": false, "protocol_version": 2}""")

        assertEquals(
            listOf("pairlie", "zero-dce-plus"),
            TestAllRunner.pendingModels(
                listOf("retinexformer-lolv1", "pairlie", "zero-dce-plus"),
                skipValid = true,
                resultsRoot = root,
            ),
        )
        assertEquals(
            listOf("retinexformer-lolv1", "pairlie", "zero-dce-plus"),
            TestAllRunner.pendingModels(
                listOf("retinexformer-lolv1", "pairlie", "zero-dce-plus"),
                skipValid = false,
                resultsRoot = root,
            ),
        )
        assertTrue(TestAllRunner.hasValidRun(root, "retinexformer-lolv1"))
        assertTrue(!TestAllRunner.hasValidRun(root, "pairlie"))
        assertTrue(!TestAllRunner.hasValidRun(root, "missing-model"))
    }
}
