package dev.humanagent.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codes must leave the device redacted: the classifier, the logs and the delivery prompt all
 * see the masked form, while ordinary content (phone numbers, order numbers, amounts) survives.
 */
class CodeRedactorTest {

    @Test
    fun announcedCodesAreMasked() {
        val masked = CodeRedactor.redact("Your verification code is 482191. Do not share it.")
        assertFalse(masked.contains("482191"))
        assertTrue(masked.contains("••••"))
        assertTrue(masked.contains("verification code"))
    }

    @Test
    fun codeAfterLabelIsMasked() {
        assertFalse(CodeRedactor.redact("GPay: use 5512 to confirm your login.").contains("5512"))
        assertFalse(CodeRedactor.redact("OTP 9042 is your bank password.").contains("9042"))
        assertFalse(CodeRedactor.redact("Your passcode: 7315").contains("7315"))
    }

    @Test
    fun bareNumbersStayWhenNobodyAsksForACode() {
        val text = "Your order 9042 arrives on 3 July; call +212 6 55 51 23 12 with questions."
        assertTrue(CodeRedactor.redact(text).contains("9042"))
        assertTrue(CodeRedactor.redact(text).contains("+212 6 55 51 23 12"))
    }

    @Test
    fun bareNumbersAreMaskedWhenTheMessageAnnouncesACode() {
        val text = "Confirm your identity. 9042"
        assertFalse(CodeRedactor.redact(text).contains("9042"))
    }

    @Test
    fun ordinaryMessagesPassThroughUnchanged() {
        val text = "Mum: on my way, bring the cake."
        assertTrue(CodeRedactor.redact(text) == "Mum: on my way, bring the cake.")
    }
}