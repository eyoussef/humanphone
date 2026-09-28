package dev.humanagent.site

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteServerTest {

    @Test
    fun servesIndexAndImagesAnd404s() {
        val root = Files.createTempDirectory("humanphone-site").toFile()
        File(root, "index.html").writeText("<h1>hello</h1>")
        File(root, "images").mkdirs()
        File(root, "images/logo.png").writeBytes(byteArrayOf(0x89.toByte(), 0x50))
        val server = SiteServer { root }
        val port = server.serve(root)
        try {
            val index = fetch(port, "/")
            assertEquals(200, index.first)
            assertEquals("text/html; charset=utf-8", index.second)
            assertTrue(index.third.contains("hello"))

            val image = fetch(port, "/images/logo.png")
            assertEquals(200, image.first)
            assertEquals("image/png", image.second)

            assertEquals(404, fetch(port, "/missing.html").first)
            assertEquals(404, fetch(port, "/%2e%2e/escape.txt").first)
        } finally {
            server.stop()
        }
    }

    private fun fetch(port: Int, path: String): Triple<Int, String, String> {
        val connection = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        try {
            val code = connection.responseCode
            val type = connection.contentType ?: ""
            val stream = if (code < 400) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return Triple(code, type, body)
        } finally {
            connection.disconnect()
        }
    }
}