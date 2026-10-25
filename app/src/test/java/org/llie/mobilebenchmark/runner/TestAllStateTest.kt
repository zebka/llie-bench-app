package org.llie.mobilebenchmark.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TestAllStateTest {

    @Test
    fun beginClearsStatusesAndMarksTheQueueRunning() {
        TestAllState.reset()
        TestAllState.setState("stale-model", "done")
        TestAllState.begin()

        assertTrue(TestAllState.running)
        assertNull(TestAllState.stateOf("stale-model"))

        TestAllState.setState("pairlie", "cooling")
        assertEquals("cooling", TestAllState.stateOf("pairlie"))
        assertEquals(mapOf("pairlie" to "cooling"), TestAllState.snapshot())

        TestAllState.finish()
        assertFalse(TestAllState.running)
        assertEquals("cooling", TestAllState.stateOf("pairlie")) // survives finish
        TestAllState.reset()
        assertNull(TestAllState.stateOf("pairlie"))
    }
}
