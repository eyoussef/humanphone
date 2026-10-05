package dev.humanagent

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * A one-connection-at-a-time HTTP server for upload/download tests: it records what arrived and
 * always answers with a canned status and body. Hand-rolled because the unit-test classpath is
 * Android's, which has no `com.sun.net.httpserver`.
 */
internal class TestHttpServer(
    private val status: Int,
    private val response: String,
    private val responseContentType: String = "application/json",
) : AutoCloseable {

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
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(" Test\r\n")
            append("Content-Type: ").append(responseContentType).append("\r\n")
            append("Content-Length: ").append(response.toByteArray().size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        socket.getOutputStream().apply {
            write(head.toByteArray())
            write(response.toByteArray())
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
        while (read < bytes.size) {
            val chunk = input.read(bytes, read, bytes.size - read)
            if (chunk <= 0) break
            read += chunk
        }
        return if (read == bytes.size) bytes else bytes.copyOf(read)
    }

    override fun close() {
        runCatching { server.close() }
    }
}