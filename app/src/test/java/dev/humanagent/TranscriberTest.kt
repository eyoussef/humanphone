package dev.humanagent

import dev.humanagent.voice.SttConfig
import dev.humanagent.voice.Transcriber
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A voice note is only worth sending when the upload is what a whisper endpoint expects: the right
 * path, the file as `file`, the model and the language as fields — and when the words come back out
 * of the answer. A throwaway socket server stands in for the endpoint, so the test checks what the
 * endpoint actually receives.
 */
class TranscriberTest {

    @Test
    fun aWhisperEndpointAnswersWithTheWordsOfTheRecording() = runBlocking {
        val file = tempRecording()
        val server = FakeEndpoint { """{"text":"  hello world  "}""" }
        try {
            val result = Transcriber().transcribe(
                SttConfig(
                    baseUrl = "http://127.0.0.1:${server.port}/v1",
                    apiKey = "sk_example",
                    model = "whisper-large-v3-turbo",
                    language = "ar",
                ),
                file,
            )

            assertEquals("hello world", result.getOrNull())
            assertEquals("Bearer sk_example", server.authorization)
            val body = server.body
            assertTrue(body.contains("POST /v1/audio/transcriptions "))
            assertTrue(body.contains("name=\"file\""))
            assertTrue(body.contains("filename=\"${file.name}\""))
            assertTrue(body.contains("Content-Type: audio/mp4"))
            assertTrue(body.contains("name=\"model\""))
            assertTrue(body.contains("whisper-large-v3-turbo"))
            assertTrue(body.contains("name=\"language\""))
            assertTrue(body.contains("\r\n\r\nar\r\n"))
        } finally {
            server.close()
            file.delete()
        }
    }

    @Test
    fun aLocalServerNeedsNeitherKeyNorLanguage() = runBlocking {
        val file = tempRecording()
        val server = FakeEndpoint { """{"text":"ok"}""" }
        try {
            val result = Transcriber().transcribe(
                SttConfig(baseUrl = "http://127.0.0.1:${server.port}/v1/", apiKey = "", model = "whisper-1"),
                file,
            )

            assertEquals("ok", result.getOrNull())
            assertNull(server.authorization)
            assertFalse(server.body.contains("name=\"language\""))
            assertTrue(server.body.contains("name=\"model\""))
        } finally {
            server.close()
            file.delete()
        }
    }

    @Test
    fun anEndpointWithoutAModelStaysOff() {
        assertFalse(SttConfig(baseUrl = "", apiKey = "", model = "whisper-1").isConfigured)
        assertFalse(SttConfig(baseUrl = "https://api.openai.com/v1", apiKey = "", model = " ").isConfigured)
        assertTrue(SttConfig(baseUrl = "https://api.openai.com/v1", apiKey = "", model = "whisper-1").isConfigured)
    }

    @Test
    fun theWordsComeOutOfTheAnswersTextField() {
        assertEquals("hello world", Transcriber.parseTranscript("""{"text":"  hello\nworld  "}"""))
    }

    @Test
    fun anAnswerWithoutTextIsNotATranscript() {
        assertNull(Transcriber.parseTranscript("""{"error":{"message":"invalid key"}}"""))
        assertNull(Transcriber.parseTranscript("<html>502 Bad Gateway</html>"))
    }

    @Test
    fun anEndpointThatCannotBeUsedComesBackAsAFailure() = runBlocking {
        val file = tempRecording()
        try {
            val result = Transcriber().transcribe(SttConfig(baseUrl = "not a url", apiKey = "", model = "w"), file)

            assertTrue(result.isFailure)
        } finally {
            file.delete()
        }
    }

    private fun tempRecording(): File = File.createTempFile("note", ".m4a").apply { writeText("audio") }

    /**
     * One request, read off the wire and answered with [answer]'s JSON: the raw multipart body is
     * kept so a test can assert what the endpoint was sent, header by header.
     */
    private class FakeEndpoint(private val answer: () -> String) {

        private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))

        @Volatile
        var body: String = ""

        @Volatile
        var authorization: String? = null

        val port: Int get() = socket.localPort

        init {
            socket.soTimeout = TIMEOUT_MS
            thread(isDaemon = true, name = "fake-endpoint") { serveOnce() }
        }

        fun close() {
            runCatching { socket.close() }
        }

        private fun serveOnce() {
            runCatching {
                socket.accept().use { client ->
                    val input = BufferedInputStream(client.getInputStream())
                    val head = readHead(input)
                    val length = header(head, "Content-Length")?.toInt() ?: 0
                    val payload = ByteArray(length)
                    var read = 0
                    while (read < length) {
                        val chunk = input.read(payload, read, length - read)
                        if (chunk < 0) break
                        read += chunk
                    }
                    body = head + String(payload, 0, read, Charsets.ISO_8859_1)
                    authorization = header(head, "Authorization")

                    val json = answer().toByteArray()
                    client.getOutputStream().apply {
                        write(
                            (
                                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                    "Content-Length: ${json.size}\r\nConnection: close\r\n\r\n"
                                ).toByteArray()
                        )
                        write(json)
                        flush()
                    }
                }
            }
        }

        private fun readHead(input: InputStream): String {
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val byte = input.read()
                if (byte < 0) break
                head.append(byte.toChar())
            }
            return head.toString()
        }

        private fun header(head: String, name: String): String? = head.lineSequence()
            .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()

        private companion object {
            const val TIMEOUT_MS = 10_000
        }
    }
}
