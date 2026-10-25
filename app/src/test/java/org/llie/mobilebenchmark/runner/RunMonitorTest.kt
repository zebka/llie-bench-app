package org.llie.mobilebenchmark.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunMonitorTest {

    @Test
    fun progressIsZeroAtTheStartAndHundredAtTheEnd() {
        assertEquals(0, progressPercent(imagesDone = 0, imagesTotal = 15, passIndex = 0, passTotal = 10))
        assertEquals(100, progressPercent(imagesDone = 15, imagesTotal = 15, passIndex = 0, passTotal = 10))
    }

    @Test
    fun progressCountsCompletedImagesAndTheCurrentPass() {
        // 7 full images (70 passes) + 4 measured passes of image 8 = 74 of 150 passes -> 49%.
        assertEquals(49, progressPercent(imagesDone = 7, imagesTotal = 15, passIndex = 4, passTotal = 10))
    }

    @Test
    fun progressIsClampedAndSafeWithAnEmptyMatrix() {
        assertEquals(0, progressPercent(imagesDone = 0, imagesTotal = 0, passIndex = 3, passTotal = 10))
        assertEquals(100, progressPercent(imagesDone = 15, imagesTotal = 15, passIndex = 12, passTotal = 10))
    }

    @Test
    fun etaIsNullUntilOnePassIsFinished() {
        assertNull(etaMs(imagesDone = 0, imagesTotal = 15, passTotal = 10, passIndex = 0, elapsedMs = 5000))
        assertNull(etaMs(imagesDone = 0, imagesTotal = 15, passTotal = 10, passIndex = 1, elapsedMs = 0))
    }

    @Test
    fun etaExtrapolatesTheMeanPassTime() {
        // One pass in 5 s out of a 150-pass matrix -> 149 passes x 5 s.
        assertEquals(745_000L, etaMs(imagesDone = 0, imagesTotal = 15, passTotal = 10, passIndex = 1, elapsedMs = 5000))
        // The very last pass leaves 0.
        assertEquals(0L, etaMs(imagesDone = 15, imagesTotal = 15, passTotal = 10, passIndex = 0, elapsedMs = 5000))
    }

    @Test
    fun durationsFormatAsMinutesSecondsOrHours() {
        assertEquals("00:00", formatDurationMs(0))
        assertEquals("04:12", formatDurationMs(252_000))
        assertEquals("1:03:05", formatDurationMs(3_785_000))
        assertEquals("--:--", formatDurationMs(-1))
    }

    @Test
    fun freqsFormatAsASlashSeparatedCoreList() {
        assertEquals("1805/2000/1700", formatFreqsMhz(listOf(1805, 2000, 1700)))
        assertEquals("n/a", formatFreqsMhz(emptyList()))
    }

    @Test
    fun medianOfHandlesOddAndEvenLists() {
        assertNull(medianOf(emptyList()))
        assertEquals(2L, medianOf(listOf(1L, 2L, 3L)))
        assertEquals(4L, medianOf(listOf(7L, 1L, 5L, 3L)))
    }

    @Test
    fun passSamplesFeedTheMedianAndResetOnBegin() {
        RunMonitor.begin("m", "cpu", "eval15", imagesTotal = 1, passTotal = 10, nowMs = 0)
        assertNull(RunMonitor.medianPassMs())
        RunMonitor.passCompleted(1, 300)
        RunMonitor.passCompleted(2, 100)
        RunMonitor.passCompleted(3, 200)
        assertEquals(200L, RunMonitor.medianPassMs())
        RunMonitor.begin("m", "cpu", "eval15", imagesTotal = 1, passTotal = 10, nowMs = 0)
        assertNull(RunMonitor.medianPassMs())
    }

    @Test
    fun beginAndTransitionsTrackTheMatrix() {
        RunMonitor.begin("pairlie", "cpu", "eval15", imagesTotal = 15, passTotal = 10, nowMs = 1000)
        assertEquals(RunMonitor.PHASE_LOADING, RunMonitor.phase)
        RunMonitor.imageStarted("55.png")
        RunMonitor.passCompleted(3, 1200)
        assertEquals(RunMonitor.PHASE_RUNNING, RunMonitor.phase)
        assertEquals("55.png", RunMonitor.currentImage)
        RunMonitor.imageFinished()
        assertEquals(1, RunMonitor.imagesDone)
        RunMonitor.complete(true, "")
        assertEquals(RunMonitor.PHASE_DONE, RunMonitor.phase)
        RunMonitor.complete(false, "OOM")
        assertEquals(RunMonitor.PHASE_FAILED, RunMonitor.phase)
        assertEquals("OOM", RunMonitor.failure)
    }
}
