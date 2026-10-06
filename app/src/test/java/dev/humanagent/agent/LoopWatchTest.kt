package dev.humanagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopWatchTest {

    @Test
    fun firstCallRunsNormally() {
        val watch = LoopWatch()
        assertNull(watch.gate("tap", """{"index":5}"""))
        assertEquals(0, watch.repeatCount("tap", """{"index":5}"""))
    }

    @Test
    fun repeatedActionIsBlockedWithGuidance() {
        val watch = LoopWatch()
        watch.recordRun("tap", """{"index":5}""")
        val blocked = watch.gate("tap", """{"index":5}""")!!
        assertTrue(blocked.contains("already ran this exact action 1x"))
        assertTrue(blocked.contains("finish"))
    }

    @Test
    fun repeatedActionWithNoopHistoryGetSharperBlock() {
        val watch = LoopWatch()
        watch.recordRun("tap", """{"index":5}""")
        watch.recordNoop("tap", """{"index":5}""")
        val blocked = watch.gate("tap", """{"index":5}""")!!
        assertTrue(blocked.contains("provably changed nothing"))
    }

    @Test
    fun differentArgumentsAreNotTheSameCall() {
        val watch = LoopWatch()
        watch.recordRun("tap", """{"index":1}""")
        assertNull(watch.gate("tap", """{"index":2}"""))
        assertNull(watch.gate("tap_text", """{"index":1}"""))
    }

    @Test
    fun argumentsAreCanonicalizedAcrossKeyOrderAndFormatting() {
        val watch = LoopWatch()
        watch.recordRun("type_text", """{"index":3, "text":"hello there"}""")
        assertNotNull(watch.gate("type_text", """{"text":"hello there","index":3}"""))
    }

    @Test
    fun provenDeadScrollIsBlockedButNormalScrollingIsNot() {
        val watch = LoopWatch()
        watch.recordRun("scroll", """{"direction":"down"}""")
        watch.recordNoop("scroll", """{"direction":"down"}""")
        assertNotNull(watch.gate("scroll", """{"direction":"down"}"""))
        // A scroll that moved the screen stays legal.
        val live = LoopWatch()
        live.recordRun("scroll", """{"direction":"down"}""")
        assertNull(live.gate("scroll", """{"direction":"down"}"""))
    }

    @Test
    fun observationToolsRepeatFreely() {
        val watch = LoopWatch()
        watch.recordRun("read_screen", "{}")
        watch.recordRun("recall", "{}")
        watch.recordNoop("read_screen", "{}")
        watch.recordNoop("recall", "{}")
        assertNull(watch.gate("read_screen", "{}"))
        assertNull(watch.gate("recall", "{}"))
        // Even a proven no-op read stays open.
        assertNull(watch.gate("read_screen", "{}"))
    }

    @Test
    fun stallWarnsThenStalls(): Unit {
        val watch = LoopWatch()
        val screen = "Screen: Gmail (10 nodes)"
        assertNull(watch.noteScreen(screen)) // 1 → streak 0 (baseline)
        assertNull(watch.noteScreen(screen)) // 2 → streak 1
        assertNull(watch.noteScreen(screen)) // 3 → streak 2
        assertNotNull(watch.noteScreen(screen)) // 4 → streak 3 → warning
        assertNotNull(watch.noteScreen(screen)) // 5 → streak 4
        assertNotNull(watch.noteScreen(screen)) // 6 → streak 5 → stalled
        assertTrue(watch.stalledTooLong())
    }

    @Test
    fun changedScreenResetsStall() {
        val watch = LoopWatch()
        repeat(4) { watch.noteScreen("Screen: Gmail (10 nodes)") }
        assertNull(watch.noteScreen("Screen: Chrome (22 nodes)"))
        assertFalse(watch.stalledTooLong())
    }
}