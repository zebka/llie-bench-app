package org.llie.mobilebenchmark

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.llie.mobilebenchmark.runner.RunMonitor
import org.llie.mobilebenchmark.runner.TestAllState
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * The monitor and the Test-all queue live in process-wide objects, so a
 * configuration change (rotation, resize, fold) must only rebuild the screen,
 * not lose the run. This recreates the activity with a live queue and checks
 * it comes back attached.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityRecreationTest {

    @After
    fun tearDown() {
        TestAllState.reset()
        RunMonitor.phase = RunMonitor.PHASE_IDLE
    }

    @Test
    fun activityRecreationKeepsTheRunningQueue() {
        TestAllState.begin()
        TestAllState.setState("zero-dce-plus", "running")
        RunMonitor.begin("zero-dce-plus", "cpu", "eval15", imagesTotal = 15, passTotal = 10, nowMs = 0)

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertNotNull(controller.get())

        controller.recreate()

        assertNotNull(controller.get())
        assertFalse(controller.get().isFinishing)
        // Process-wide state survives the rebuild.
        org.junit.Assert.assertTrue(TestAllState.running)
        org.junit.Assert.assertEquals("running", TestAllState.stateOf("zero-dce-plus"))
    }
}
