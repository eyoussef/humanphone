package dev.humanagent.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hourly ceiling that stops a noisy app from turning Auto mode into a quota burner.
 * Every test gets a fresh governor; the `now` clock is a local so tests cannot share a bucket.
 */
class AutoModeGovernorTest {

    private class Clock {
        var now: Long = 0L
    }

    private fun governor(clock: Clock) = AutoModeGovernor({ clock.now }, log = {})

    @Test
    fun classifyCeilingStopsTheFlood() {
        val clock = Clock()
        val governor = governor(clock)
        repeat(AutoModeGovernor.MAX_CLASSIFY_PER_HOUR) { index ->
            clock.now = index * 100L
            assertTrue("call $index should pass", governor.mayClassify())
        }
        clock.now = AutoModeGovernor.MAX_CLASSIFY_PER_HOUR * 100L
        assertFalse(governor.mayClassify())
    }

    @Test
    fun theWindowSlidesSoOldCallsStopCounting() {
        val clock = Clock()
        val governor = governor(clock)
        repeat(AutoModeGovernor.MAX_CLASSIFY_PER_HOUR) { index ->
            clock.now = index * 100L
            governor.mayClassify()
        }
        // One full hour past the last stamp (at (MAX-1)*100) the bucket is empty again.
        clock.now = AutoModeGovernor.WINDOW_MS + AutoModeGovernor.MAX_CLASSIFY_PER_HOUR * 100L
        assertTrue(governor.mayClassify())
    }

    @Test
    fun runsCeilingIsTighterThanClassifications() {
        val clock = Clock()
        val governor = governor(clock)
        repeat(AutoModeGovernor.MAX_RUNS_PER_HOUR) { governor.mayRun() }
        assertFalse(governor.mayRun())
        // Classifying is still possible after runs are out: a skip decision costs seconds.
        assertTrue(governor.mayClassify())
    }
}