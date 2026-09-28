package dev.humanagent

import dev.humanagent.llm.SttMode
import dev.humanagent.voice.RemoteTranscriber
import dev.humanagent.voice.SttConfig
import dev.humanagent.voice.wavHeader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
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

/**
 * A one-connection-at-a-time HTTP server for the upload test: it records what arrived and always
 * answers with a canned status and body. Hand-rolled because the unit-test classpath is Android's,
 * which has no `com.sun.net.httpserver`.
 */
private class TestHttpServer(private val status: Int, private val response: String) : AutoCloseable {

    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))

    val port: Int get() = server.localPort

    val path = AtomicReference("")
    val authorization = AtomicReference("")
    val body = AtomicReference("")

    fun start() {
        thread(isDaemon = true, name = "test-http") {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                socket.use { handle(it) }
            }
        }
    }

    private fun handle(socket: Socket) {
        val input = socket.getInputStream()
        val requestLine = readLine(input) ?: return
        path.set(requestLine.split(' ').getOrNull(1).orEmpty())
        var contentLength = 0
        while (true) {
            val header = readLine(input) ?: break
            if (header.isEmpty()) break
            if (header.startsWith("Authorization:", ignoreCase = true)) {
                authorization.set(header.substringAfter(':').trim())
            }
            if (header.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = header.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }
        body.set(String(readBody(input, contentLength), Charsets.ISO_8859_1))
        val payload = response.toByteArray()
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(" Test\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ").append(payload.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        socket.getOutputStream().apply {
            write(head.toByteArray())
            write(payload)
            flush()
        }
    }

    private fun readLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (line.size() == 0) null else line.toString("ISO-8859-1")
            if (byte == '\n'.code) return line.toString("ISO-8859-1")
            if (byte != '\r'.code) line.write(byte)
        }
    }

    private fun readBody(input: InputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val chunk = input.read(bytes, read, length - read)
            if (chunk <= 0) break
            read += chunk
        }
        return bytes
    }

    override fun close() {
        runCatching { server.close() }
    }
}
