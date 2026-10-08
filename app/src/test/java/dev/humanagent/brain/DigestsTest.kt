package dev.humanagent.brain

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DigestsTest {

    @Test
    fun sha256MatchesKnownVector() {
        val file = File.createTempFile("digest", ".bin")
        file.writeBytes("abc".toByteArray())
        // FIPS 180-2 test vector for "abc".
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Digests.sha256(file))
        file.delete()
    }

    @Test
    fun matchesIsCaseInsensitiveAndRejectsAnythingElse() {
        val file = File.createTempFile("digest", ".bin")
        file.writeBytes("abc".toByteArray())
        val hash = Digests.sha256(file)
        assertTrue(Digests.matches(file, hash))
        assertTrue(Digests.matches(file, hash.uppercase()))
        assertFalse(Digests.matches(file, "0".repeat(64)))
        assertFalse(Digests.matches(File(file.parentFile, "missing.bin"), hash))
        file.delete()
    }
}