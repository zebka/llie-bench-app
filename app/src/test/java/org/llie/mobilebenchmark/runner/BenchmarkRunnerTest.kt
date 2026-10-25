package org.llie.mobilebenchmark.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.llie.mobilebenchmark.model.ModelEngine
import org.llie.mobilebenchmark.model.ModelManifest
import org.llie.mobilebenchmark.model.RgbImage
import org.llie.mobilebenchmark.model.TimedOutput
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class BenchmarkRunnerTest {

    private val image = RgbImage.zeros(2, 2)

    private class FakeEngine(private val phases: TimedOutput) : ModelEngine {
        override fun load(modelFile: File, manifest: ModelManifest) = Unit

        override fun infer(input: RgbImage): RgbImage = phases.image

        override fun inferTimed(input: RgbImage): TimedOutput = phases

        override fun close() = Unit
    }

    @Test
    fun measureKeepsWallTimeAndRecordsTheThreePhasesSeparately() {
        val engine = FakeEngine(TimedOutput(image, prepareNs = 500, runNs = 400, unpackNs = 100))
        val clock = ArrayDeque(listOf(100L, 110L, 1000L, 1010L))
        val runner = BenchmarkRunner(
            engine,
            BenchmarkConfig(warmupCount = 0, measuredPasses = 1),
            clock = { clock.removeFirst() },
        )

        val result = runner.measure("1.png", image)

        assertTrue(result.success)
        assertEquals(listOf(890L), result.modelNs)
        assertEquals(listOf(500L), result.prepareNs)
        assertEquals(listOf(400L), result.runNs)
        assertEquals(listOf(100L), result.unpackNs)
    }

    @Test
    fun phaseSamplesAreEmptyWhenTheEngineFails() {
        val engine = object : ModelEngine {
            override fun load(modelFile: File, manifest: ModelManifest) = Unit
            override fun infer(input: RgbImage): RgbImage = throw IllegalStateException("boom")
            override fun inferTimed(input: RgbImage): TimedOutput = throw IllegalStateException("boom")
            override fun close() = Unit
        }
        val clock = ArrayDeque(listOf(100L, 110L, 120L, 130L))
        val runner = BenchmarkRunner(
            engine,
            BenchmarkConfig(warmupCount = 0, measuredPasses = 1),
            clock = { clock.removeFirst() },
        )

        val result = runner.measure("1.png", image)

        assertTrue(!result.success)
        assertTrue(result.modelNs.isEmpty())
        assertTrue(result.prepareNs.isEmpty())
        assertTrue(result.runNs.isEmpty())
        assertTrue(result.unpackNs.isEmpty())
    }

    @Test
    fun defaultProtocolRunsTenMeasuredPasses() {
        assertEquals(10, BenchmarkConfig().measuredPasses)
        assertEquals(2, BenchmarkConfig().protocolVersion)
    }

    @Test
    fun measureReportsEachMeasuredPassThroughTheOptionalCallback() {
        val engine = FakeEngine(TimedOutput(image, prepareNs = 1, runNs = 1, unpackNs = 1))
        val clock = ArrayDeque(
            listOf(
                0L, 0L, 5_000_000L, 5_000_000L,                       // pass 1: e2e 5 ms
                10_000_000L, 10_000_000L, 12_000_000L, 12_000_000L,   // pass 2: e2e 2 ms
            ),
        )
        val runner = BenchmarkRunner(
            engine,
            BenchmarkConfig(warmupCount = 0, measuredPasses = 2),
            clock = { clock.removeFirst() },
        )
        val seen = mutableListOf<Pair<Int, Long>>()

        val result = runner.measure("1.png", image, onPass = { index, ms -> seen.add(index to ms) })

        assertTrue(result.success)
        assertEquals(listOf(1 to 5L, 2 to 2L), seen)
    }
}
