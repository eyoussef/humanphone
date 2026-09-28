package dev.humanagent

import dev.humanagent.voice.wavHeader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The upload the transcription endpoint receives must be a well-formed WAV, so the header has to
 * describe the payload exactly: wrong sizes or rates make the server reject the recording.
 */
class WavHeaderTest {

    @Test
    fun headerDescribesTheRecordedFormatAndPayload() {
        val pcmBytes = 32_000L
        val header = wavHeader(pcmBytes, sampleRate = 16_000, channels = 1, bitsPerSample = 16)

        assertEquals(44, header.size)
        assertEquals("RIFF", ascii(header, 0))
        assertEquals("WAVE", ascii(header, 8))
        assertEquals("fmt ", ascii(header, 12))
        assertEquals("data", ascii(header, 36))
        assertEquals(36 + pcmBytes, leInt(header, 4).toLong())
        assertEquals(16, leInt(header, 16))
        assertEquals(1, leShort(header, 20).toInt())
        assertEquals(1, leShort(header, 22).toInt())
        assertEquals(16_000, leInt(header, 24))
        assertEquals(32_000, leInt(header, 28))
        assertEquals(2, leShort(header, 32).toInt())
        assertEquals(16, leShort(header, 34).toInt())
        assertEquals(pcmBytes, leInt(header, 40).toLong())
    }

    @Test
    fun aShortCaptureStillGetsItsOwnSizes() {
        val header = wavHeader(7, sampleRate = 16_000, channels = 1, bitsPerSample = 16)
        assertEquals(36 + 7, leInt(header, 4))
        assertEquals(7, leInt(header, 40))
    }

    private fun ascii(bytes: ByteArray, offset: Int): String =
        String(bytes, offset, 4, Charsets.US_ASCII)

    private fun leInt(bytes: ByteArray, offset: Int): Int = littleEndian(bytes).getInt(offset)

    private fun leShort(bytes: ByteArray, offset: Int): Short = littleEndian(bytes).getShort(offset)

    private fun littleEndian(bytes: ByteArray): ByteBuffer =
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
}
