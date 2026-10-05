package dev.humanagent

import dev.humanagent.llm.SttMode
import dev.humanagent.voice.RemoteTranscriber
import dev.humanagent.voice.SttConfig
import dev.humanagent.voice.wavHeader
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dictation upload path: a recorded WAV must leave the app as a multipart request to
 * `<baseUrl>/audio/transcriptions` and the answer's `text` must come back as the transcript.
 */
class RemoteTranscriberTest {

    @Test
    fun theEndpointIsDerivedFromTheBaseUrl() {
        val root = SttConfig(mode = SttMode.REMOTE, baseUrl = "https://api.openai.com/v1/")
        assertEquals("https://api.openai.com/v1/audio/transcriptions", root.transcriptionsUrl)
        val full = SttConfig(mode = SttMode.REMOTE, baseUrl = "http://127.0.0.1:8080/v1/audio/transcriptions")
        assertEquals("http://127.0.0.1:8080/v1/audio/transcriptions", full.transcriptionsUrl)
        assertFalse(root.isRemoteUsable)
        assertTrue(root.copy(model = "whisper-1").isRemoteUsable)
    }

    @Test
    fun theRecordingIsPostedAndTheTranscriptComesBack() {
        val server = TestHttpServer(status = 200, response = """{"text":"  send a message to sam  "}""")
        server.start()
        val wav = recording(3_200)
        try {
            val config = SttConfig(
                mode = SttMode.REMOTE,
                baseUrl = "http://127.0.0.1:${server.port}/v1",
                apiKey = "secret",
                model = "whisper-1",
                language = "en",
            )

            val result = runBlocking { RemoteTranscriber().transcribe(config, wav) }

            assertEquals("send a message to sam", result.getOrThrow())
            assertEquals("/v1/audio/transcriptions", server.path.get())
            assertEquals("Bearer secret", server.authorization.get())
            val body = server.body.get()
            assertTrue(body.contains("name=\"model\""))
            assertTrue(body.contains("whisper-1"))
            assertTrue(body.contains("name=\"language\""))
            assertTrue(body.contains("filename=\"audio.wav\""))
            assertTrue(body.contains("RIFF"))
        } finally {
            server.close()
            wav.delete()
        }
    }

    @Test
    fun aRejectedUploadFailsWithTheStatusCode() {
        val server = TestHttpServer(status = 401, response = """{"error":{"message":"invalid api key"}}""")
        server.start()
        val wav = recording(320)
        try {
            val config = SttConfig(
                mode = SttMode.REMOTE,
                baseUrl = "http://127.0.0.1:${server.port}",
                model = "whisper-1",
            )

            val result = runBlocking { RemoteTranscriber().transcribe(config, wav) }

            val message = result.exceptionOrNull()?.message.orEmpty()
            assertTrue(message.contains("401"))
            assertTrue(message.contains("invalid api key"))
        } finally {
            server.close()
            wav.delete()
        }
    }

    private fun recording(pcmBytes: Int): File = File.createTempFile("humanphone-dictation", ".wav").apply {
        writeBytes(wavHeader(pcmBytes.toLong(), 16_000, 1, 16) + ByteArray(pcmBytes))
    }
}
