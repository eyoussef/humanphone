package dev.humanagent.site

import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A tiny static file server bound to the phone's loopback interface, so the assistant can show
 * the site it just built in a real browser: `preview_site` starts it over the site directory and
 * opens `http://127.0.0.1:<port>/`, where `index.html` and its `images/…` references just work.
 *
 * One thread, one connection at a time, `Connection: close` on every response — the traffic is
 * one local viewer, so this is deliberately the most boring server possible.
 */
class SiteServer(private val rootProvider: () -> File) {

    private var socket: ServerSocket? = null
    private var worker: Thread? = null
    private val running = AtomicBoolean(false)

    /** Starts (or retargets) the server; returns the local port it listens on. */
    fun start(): Int {
        ensureRunning()
        return socket?.localPort ?: error("server did not start")
    }

    /** Points the running server at another site directory; starts it when stopped. */
    fun serve(root: File): Int {
        currentRoot = root.apply { mkdirs() }
        return start()
    }

    @Volatile
    private var currentRoot: File? = null

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        worker = null
    }

    fun isRunning(): Boolean = running.get() && socket != null

    private fun ensureRunning() {
        if (isRunning()) return
        val server = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        socket = server
        running.set(true)
        worker = Thread({ acceptLoop(server) }, "humanphone-site-server").apply {
            isDaemon = true
            start()
        }
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running.get()) {
            val client = runCatching { server.accept() }.getOrNull() ?: break
            handle(client)
        }
    }

    private fun handle(client: Socket) {
        client.use { connection ->
            connection.soTimeout = 5_000
            val reader = connection.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            // Drain the rest of the request (headers) so the client is never left blocked.
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val path = requestLine.split(" ").getOrNull(1)?.take(2_000) ?: return
            respond(connection, path)
        }
    }

    private fun respond(connection: Socket, rawPath: String) {
        val out = connection.getOutputStream()
        val root = currentRoot ?: rootProvider()
        val path = rawPath.substringBefore('?').ifEmpty { "/" }
        val file = resolve(root, path)
        if (file == null || !file.isFile) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 9\r\nConnection: close\r\n\r\nnot found".toByteArray())
            out.flush()
            return
        }
        val body = file.readBytes()
        val head = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: ${mimeOf(file)}\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }

    private fun resolve(root: File, rawPath: String): File {
        val cleaned = java.net.URLDecoder.decode(rawPath.substringBefore('?'), Charsets.UTF_8)
            .removePrefix("/")
            .take(240)
        if (cleaned.contains("..")) return File(root, "__refused__")
        val target = File(root, cleaned.ifEmpty { "index.html" }).canonicalFile
        val rootCanon = root.canonicalPath + File.separator
        if (!target.path.startsWith(rootCanon)) return File(root, "__refused__")
        if (target.isDirectory) {
            // The leaf is resolved after the directory check, so it gets its own containment
            // test too — a symlinked index.html inside a site directory must not lead outside.
            val leaf = File(target, "index.html").canonicalFile
            if (!leaf.path.startsWith(rootCanon)) return File(root, "__refused__")
            return leaf
        }
        return target
    }

    private fun mimeOf(file: File): String = when (file.extension.lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js" -> "text/javascript; charset=utf-8"
        "json" -> "application/json"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "ico" -> "image/x-icon"
        "svgz" -> "image/svg+xml"
        "woff2" -> "font/woff2"
        "txt" -> "text/plain; charset=utf-8"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }
}