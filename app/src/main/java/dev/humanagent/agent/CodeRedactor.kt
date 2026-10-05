package dev.humanagent.agent

/**
 * Redacts verification codes out of notification text before that text travels anywhere.
 *
 * The watchdog needs to know that a message *is* a code message; it never needs the code
 * itself. Everything the classifier sees is also shipped to whatever endpoint the user
 * configured, subject to that provider's logging and retention — so a bank one-time password
 * must never be in the request body at all. Redaction happens at the event's birth: the
 * [NotificationEvent.text] itself is masked, so every consumer down the line (describe,
 * classify, logs, delivery prompts) sees the safe form.
 */
internal object CodeRedactor {

    /**
     * A code is a short digit run standing in the text on its own, or right after the words
     * that announce one. Long digit runs are deliberately untouched: phone numbers, order
     * numbers and amounts are payload, not codes, and mangling them would break replies.
     */
    private val ANNOUNCED_CODE = Regex(
        "(?i)\\b((?:verification|confirm(?:ation)?|security|login|auth|access|one[- ]time|otp|pin|code|passcode|2fa)\\s*" +
            "(?:code|number|is)?)\\s*[:：=\\-—~>]*\\s*([0-9]{3,8}|[0-9A-Z]{4,8})\\b",
    )
    private val BARE_CODE = Regex("(?<![0-9A-Za-z])([0-9]{4,8})(?![0-9A-Za-z])")
    private val CODE_ANNOUNCERS = setOf(
        "code", "verification", "verify", "otp", "pin", "password", "passcode", "confirm", "confirmation",
    )
    private const val MASK = "••••"

    /** The notification text with every verification code replaced. Pure, so it unit tests. */
    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var result = ANNOUNCED_CODE.replace(text) { match ->
            "${match.groupValues[1]} $MASK [this is a verification code — never repeat, never type it]"
        }
        // A standalone 4-8 digit run that the announcer words mention elsewhere in the text —
        // covers layouts where the code and its label are separated ("Your code: G-4821", "4821").
        val mentionsAnnouncer = result.split(Regex("\\s+")).any { it.trim(':', '!', '.', ',').lowercase() in CODE_ANNOUNCERS }
        if (mentionsAnnouncer) {
            result = BARE_CODE.replace(result) { match -> MASK }
        }
        // Codes are always machine sent: the classifier's skip rules already route this to skip,
        // and now they route it without the secret.
        return if (mentionsAnnouncer) "$result" else result
    }
}