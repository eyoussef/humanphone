package dev.humanagent.voice

import dev.humanagent.TestHttpServer
import dev.humanagent.llm.TtsMode
import java.io.File
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The spoken-reply path: an OpenAI-compatible `/audio/speech` request must carry model, input,
 * voice and response format, and the audio answer must come back as a playable file. Failures
 * must arrive as readable sentences in the same style as the dictation upload.
 */
class RemoteSpeechTest {

    @Test
    fun theSpeechEndpointIsDerivedFromTheBaseUrl() {
        val root = TtsConfig(mode = TtsMode.REMOTE, baseUrl = "https://api.openai.com/v1/")
        assertEquals("https://api.openai.com/v1/audio/speech", root.speechUrl)
        val full = TtsConfig(mode = TtsMode.REMOTE, baseUrl = "http://127.0.0.1:8080/v1/audio/speech")
        assertEquals("http://127.0.0.1:8080/v1/audio/speech", full.speechUrl)
        assertFalse(root.isRemoteUsable)
        assertTrue(root.copy(model = "gpt-4o-mini-tts").isRemoteUsable)
        assertFalse(TtsConfig(mode = TtsMode.LOCAL).isRemoteUsable)
    }

    @Test
    fun theRequestCarriesModelInputVoiceAndFormatAndTheAudioFileComesBack() {
        val server = TestHttpServer(status = 200, response = "MP3BYTES", responseContentType = "audio/mpeg")
        server.start()
        val out = File.createTempFile("humanphone-speech", ".mp3")
        try {
            val config = TtsConfig(
                mode = TtsMode.REMOTE,
                baseUrl = "http://127.0.0.1:${server.port}/v1",
                apiKey = "secret",
                model = "gpt-4o-mini-tts",
                voice = "nova",
            )

            val result = runBlocking { RemoteSpeechFetcher().audio(config, "hello there", out) }

            assertEquals(out, result.getOrThrow())
            assertEquals("MP3BYTES".length.toLong(), out.length())
            assertEquals("/v1/audio/speech", server.path.get())
            assertEquals("Bearer secret", server.authorization.get())
            val body = server.body.get()
            assertTrue(body.contains("\"model\":\"gpt-4o-mini-tts\""))
            assertTrue(body.contains("\"input\":\"hello there\""))
            assertTrue(body.contains("\"voice\":\"nova\""))
            assertTrue(body.contains("\"response_format\":\"mp3\""))
        } finally {
            server.close()
            out.delete()
        }
    }

    @Test
    fun aBlankVoiceFallsBackToTheDefault() {
        val server = TestHttpServer(status = 200, response = "MP3BYTES", responseContentType = "audio/mpeg")
        server.start()
        val out = File.createTempFile("humanphone-speech", ".mp3")
        try {
            val config = TtsConfig(mode = TtsMode.REMOTE, baseUrl = "http://127.0.0.1:${server.port}/v1", model = "tts-1")

            runBlocking { RemoteSpeechFetcher().audio(config, "hello", out) }.getOrThrow()

            assertTrue(server.body.get().contains("\"voice\":\"alloy\""))
            // No key configured, no header sent: local servers need no credentials.
            assertEquals("", server.authorization.get())
        } finally {
            server.close()
            out.delete()
        }
    }

    @Test
    fun aRejectedRequestFailsWithTheStatusCodeAndSnippet() {
        val server = TestHttpServer(status = 401, response = """{"error":{"message":"bad key"}}""")
        server.start()
        val dir = java.nio.file.Files.createTempDirectory("humanphone-speech").toFile()
        val out = File(dir, "speech.mp3")
        try {
            val config = TtsConfig(mode = TtsMode.REMOTE, baseUrl = "http://127.0.0.1:${server.port}/v1", model = "tts-1")

            val result = runBlocking { RemoteSpeechFetcher().audio(config, "hello", out) }

            val message = result.exceptionOrNull()?.message.orEmpty()
            assertTrue(message.contains("401"))
            assertTrue(message.contains("bad key"))
            assertFalse(out.exists())
        } finally {
            server.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun aServerThatAnswersErrorJsonWithA200IsNotTreatedAsAudio() {
        val server = TestHttpServer(status = 200, response = """{"error":"quota burned"}""")
        server.start()
        val dir = java.nio.file.Files.createTempDirectory("humanphone-speech").toFile()
        val out = File(dir, "speech.mp3")
        try {
            val config = TtsConfig(mode = TtsMode.REMOTE, baseUrl = "http://127.0.0.1:${server.port}/v1", model = "tts-1")

            val result = runBlocking { RemoteSpeechFetcher().audio(config, "hello", out) }

            val message = result.exceptionOrNull()?.message.orEmpty()
            assertTrue(message.contains("not audio"))
            assertTrue(message.contains("quota burned"))
            assertFalse(out.exists())
        } finally {
            server.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun aConfigWithoutAModelFailsWithoutTalkingToTheNetwork() {
        val out = File.createTempFile("humanphone-speech", ".mp3")

        val result = runBlocking {
            RemoteSpeechFetcher().audio(TtsConfig(mode = TtsMode.REMOTE, model = ""), "hello", out)
        }

        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("No speech model"))
    }

    @Test
    fun theSpeechLanguageFallsBackThroughTheTag() {
        assertEquals(Locale("ar"), ttsLocale("ar", fallback = Locale.US))
        assertEquals(Locale("pt", "BR"), ttsLocale("pt-BR", fallback = Locale.US))
        assertEquals(Locale.US, ttsLocale("", fallback = Locale.US))
        assertEquals(Locale.US, ttsLocale("   ", fallback = Locale.US))
    }
}