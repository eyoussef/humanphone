package dev.humanagent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vault seals API keys before they reach the datastore. The crypto lives in the Android
 * Keystore and cannot run in a JVM test; what is pinned here is the legacy-detection contract
 * that decides whether a stored value is opened or passed through untouched, and that a corrupt
 * box degrades to "missing settings" instead of wedging the flow.
 */
class SecretVaultTest {

    @Test
    fun legacyPlaintextPassesThroughUnopened() {
        val legacy = """{"providerKind":"OLLAMA"}"""
        assertFalse(SecretVault.isSealed(legacy))
        assertEquals(legacy, SecretVault.open(legacy).getOrDefault(""))
    }

    @Test
    fun sealedValuesAreRecognized() {
        assertTrue(SecretVault.isSealed("v1:AAAA:BBBB"))
        assertFalse(SecretVault.isSealed("""{"providerKind":"OLLAMA"}"""))
    }

    @Test
    fun malformedSealedValuesDegradeToDefaults() {
        assertTrue(SecretVault.open("v1:not-base64:also-not").isFailure)
        assertTrue(SecretVault.open("v1:only-one-part").isFailure)
        // The caller reads the failure as a missing value; the settings flow keeps working.
        assertEquals(null, SecretVault.open("v1:AAAA:BBBB").getOrNull())
    }
}