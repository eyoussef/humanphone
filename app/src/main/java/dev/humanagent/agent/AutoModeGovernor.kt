package dev.humanagent.agent

import android.util.Log

/**
 * A ceiling on what Auto mode may do per hour, and where. Without it, any app the user has
 * installed can post distinct notification after distinct notification — each one a fresh
 * fingerprint, each one a classifier call, each actionable verdict a full agent run — and the
 * user's provider quota and battery pay for it. The ceiling bounds both.
 *
 * One bucket, sliding hour, in-memory: it exists to stop abuse and runaway loops, not to meter
 * a paying customer. A restart resets it — restarts need the process alive anyway, and the
 * watchdog's fingerprint/echo cooldowns carry the short-window work in the same process.
 */
class AutoModeGovernor(
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Log sink, replaceable so the ceiling logic unit tests without the Android framework. */
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {

    private val classifyTimestamps = ArrayDeque<Long>()
    private val runTimestamps = ArrayDeque<Long>()
    private var nextLogAtMs = 0L

    /** True when a classifier call may proceed; otherwise the notification is left unanswered. */
    fun mayClassify(): Boolean = admit(classifyTimestamps, MAX_CLASSIFY_PER_HOUR, "classify")

    /** True when an agent run may start for this verdict; otherwise the reply is dropped. */
    fun mayRun(): Boolean = admit(runTimestamps, MAX_RUNS_PER_HOUR, "run")

    private fun admit(bucket: ArrayDeque<Long>, limit: Int, kind: String): Boolean {
        val now = nowMs()
        // Strictly older than the horizon falls out of the hour; a stamp exactly one hour
        // ago is inside the window, matching how a user would read "per hour".
        val horizon = now - WINDOW_MS
        while (bucket.isNotEmpty() && bucket.first() <= horizon) bucket.removeFirst()
        if (bucket.size >= limit) {
            if (now >= nextLogAtMs) {
                log("$kind ceiling reached ($limit/hour); holding back until the window clears")
                nextLogAtMs = now + LOG_THROTTLE_MS
            }
            return false
        }
        bucket.addLast(now)
        return true
    }

    companion object {
        const val WINDOW_MS = 60 * 60 * 1000L
        const val MAX_CLASSIFY_PER_HOUR = 60
        const val MAX_RUNS_PER_HOUR = 12
        private const val LOG_THROTTLE_MS = 60_000L
        private const val TAG = "HumanPhoneAuto"
    }
}