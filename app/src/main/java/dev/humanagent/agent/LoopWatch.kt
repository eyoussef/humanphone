package dev.humanagent.agent

import dev.humanagent.util.JsonArgs

/**
 * Detects the run going in circles so [AgentLoop] can steer or stop it: the same tools with the
 * same arguments over and over, or steps spent on a screen that stopped changing.
 *
 * Pure logic over the current run's inputs; AgentLoop owns nudges and the transcript.
 */
class LoopWatch {

    /** Canonical signature of every executed call, in run order (multiplicity is the repeat count). */
    private val runs = mutableListOf<String>()

    /** Signatures whose execution provably left the screen unchanged. */
    private val noops = mutableSetOf<String>()

    /** Consecutive steps with identical screen content. */
    private var sameScreenStreak = 0
    private var previousScreenFingerprint: Int? = null

    /** How many times this exact call (name + canonical arguments) already executed. */
    fun repeatCount(name: String, args: String): Int {
        val signature = canonical(name, args)
        return runs.count { it == signature }
    }

    /** Registers that this call executed. */
    fun recordRun(name: String, args: String) {
        runs.add(canonical(name, args))
    }

    /** Marks the last run of [name]/[args] as a proven no-op (screen unchanged after it). */
    fun recordNoop(name: String, args: String) {
        if (runs.isNotEmpty() && (name in MUTATING || name in SCROLLING)) {
            noops.add(canonical(name, args))
        }
    }

    /**
     * What to do with this call before running it: non-null text is a block — the agent gets the
     * text as the tool result, the tool does not run. Null means run the tool normally.
     *
     * - Observation and store tools are re-run freely (reading, waiting, speaking).
     * - Scrolls repeat legitimately (long lists) but a scroll that provably moved nothing is dead.
     * - Destructive tools (SMS, call, alarm) are never re-run with the same arguments.
     * - Everything else is blocked once it repeats, more sharply each time.
     */
    fun gate(name: String, args: String): String? {
        val signature = canonical(name, args)
        val times = repeatCount(name, args)
        return when {
            name in OBSERVATION -> null

            name in SCROLLING -> if (signature in noops) noopBlock(signature) else null

            times >= 1 -> blockText(name, times, signature)

            else -> null
        }
    }

    private fun blockText(name: String, times: Int, signature: String): String = when (signature in noops) {
        true ->
            "This exact action already ran and provably changed nothing on the screen, so it is blocked. " +
                "Look at the latest dump, pick a different approach, or call finish and say where you are."
        false ->
            "You already ran this exact action ${times}x this task. Do not repeat what you did — " +
                "continue to the next step toward the goal, or call finish with a short summary if the task is done."
    }

    private fun noopBlock(signature: String): String =
        "This exact action already ran and the screen did not change, so it is blocked — the same swipe will not help. " +
            "Use scroll_to_text for a specific target, or call finish with a short summary."

    /** Feeds one step's screen text; returns a nudge once identical content repeats long enough. */
    fun noteScreen(rendered: String): String? {
        val fingerprint = rendered.hashCode()
        sameScreenStreak = if (fingerprint == previousScreenFingerprint) sameScreenStreak + 1 else 0
        previousScreenFingerprint = fingerprint
        return if (sameScreenStreak >= STALL_WARNING_STREAK) {
            "The screen has not changed for $sameScreenStreak steps, so what you are doing is not working. " +
                "Change your approach — different tool or different screen — or call finish and say what blocks you."
        } else {
            null
        }
    }

    /** True when the screen has been identical for [STALL_STOP_STREAK] steps; time to stop honestly. */
    fun stalledTooLong(): Boolean = sameScreenStreak >= STALL_STOP_STREAK

    companion object {
        const val STALL_WARNING_STREAK = 3
        const val STALL_STOP_STREAK = 5

        /** Tools safe to repeat: they observe, wait or store, and repeats do not damage anything. */
        val OBSERVATION = setOf(
            "read_screen", "find_text", "recall", "app_skill", "list_apps", "list_site_files",
            "wait", "speak", "compare_offers", "remember", "save_skill", "write_site_file",
            "download_image", "create_site", "record_offer",
        )

        /** Tools that legitimately repeat because lists scroll one step at a time. */
        val SCROLLING = setOf("scroll", "scroll_in", "scroll_to_text")

        /** Tools whose whole point is changing the screen; unchanged output proves a no-op. */
        val MUTATING = setOf(
            "tap", "tap_text", "long_press", "type_text", "clear_text", "press_enter",
            "navigate", "open_app", "open_url", "system_settings",
        )

        /** Stable signature of one call: name plus its arguments canonically joined. */
        fun canonical(name: String, args: String): String {
            val parts = JsonArgs.asObject(args)?.entries
                ?.sortedBy { it.key }
                ?.joinToString(",") { (key, value) -> "$key=${value.toString().take(120)}" }
                .orEmpty()
            return "$name($parts)"
        }
    }
}