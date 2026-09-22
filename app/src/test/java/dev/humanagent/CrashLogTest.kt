package dev.humanagent

import dev.humanagent.diag.CrashLog
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crash report is the only evidence left after the app dies, so it has to carry the breadcrumbs
 * that led to the throwable and the throwable itself with its causes — a truncated or reordered
 * report would send the next reader hunting blind.
 */
class CrashLogTest {

    @Test
    fun reportKeepsTheTrailAboveTheThrowableAndItsCauses() {
        val error = IllegalStateException("service refused to start", IllegalArgumentException("no permission"))
        val marks = CrashLog.markText("2026-09-22 16:00:00.000", "main", "application created") + "\n"

        val text = CrashLog.reportText(
            header = "app: dev.humanagent.debug 1.0\n",
            marks = marks,
            thread = "main",
            error = error,
        )

        assertTrue(text.contains(marks))
        assertTrue(text.indexOf("application created") < text.indexOf("java.lang.IllegalStateException"))
        assertTrue(text.contains("Caused by: java.lang.IllegalArgumentException: no permission"))
        assertTrue(text.contains("at dev.humanagent.CrashLogTest"))
    }
}
