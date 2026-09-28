package dev.humanagent.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.math.max
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Copies what the user attaches into app storage so a conversation keeps working after the source
 * document is gone, and turns stored attachments into the shapes a model request needs.
 *
 * Nothing here throws out of the public API: every failure is either a [Result.failure] or a `null`.
 */
class AttachmentStore(private val context: Context) {

    private val dir = File(context.filesDir, DIRECTORY)

    /** Copies [uri] into app storage and describes the copy. */
    suspend fun import(
        uri: Uri,
        displayName: String? = null,
        mimeType: String? = null,
    ): Result<Attachment> = withContext(Dispatchers.IO) {
        runCatching { importLocked(uri, displayName, mimeType) }
    }

    /** Base64 JPEG of a stored image, or `null` when the copy is gone or unexpectedly large. */
    suspend fun imageBase64(attachment: Attachment): String? = withContext(Dispatchers.IO) {
        encode(attachment, MAX_FILE_BYTES)
    }

    /** UTF-8 text of a stored attachment, trimmed and cut to [limit] characters. */
    suspend fun text(attachment: Attachment, limit: Int = TEXT_INLINE_LIMIT): String? =
        withContext(Dispatchers.IO) {
            val file = storedFile(attachment) ?: return@withContext null
            runCatching {
                val bytes = file.inputStream().use { readCapped(it, limit * UTF8_MAX_BYTES) }
                bytes.decodeToString().trim().take(limit)
            }.getOrNull()
        }

    /** Base64 of a stored attachment, or `null` when it is gone or larger than [limitBytes]. */
    suspend fun base64(attachment: Attachment, limitBytes: Int = MAX_FILE_BYTES): String? =
        withContext(Dispatchers.IO) {
            encode(attachment, limitBytes)
        }

    /** Removes the stored copy; the conversation keeps the description of it. */
    suspend fun delete(attachment: Attachment) = withContext(Dispatchers.IO) {
        runCatching { File(attachment.path).delete() }
        Unit
    }

    /** One-line description of a selection, e.g. `photo.jpg (image), notes.txt (text)`. */
    fun summary(attachments: List<Attachment>): String =
        attachments.joinToString(", ") { "${it.name} (${it.kind})" }

    private fun importLocked(uri: Uri, displayName: String?, mimeType: String?): Attachment {
        val name = displayName?.takeIf { it.isNotBlank() } ?: resolveName(uri)
        val mime = mimeType?.takeIf { it.isNotBlank() }
            ?: runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()
        dir.mkdirs()
        val id = newId()
        return when {
            isImage(mime, name) -> storeImage(uri, id, name)
            isText(mime, name) -> storeText(uri, id, name, mime)
            else -> storeFile(uri, id, name, mime)
        }
    }

    private fun storeImage(uri: Uri, id: String, name: String): Attachment {
        // Two-pass decode: bounds first, then a sampled decode, so a large photo never lands whole
        // in memory before it is scaled down.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalArgumentException("\"$name\" could not be read as an image.")
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
        }
        val decoded = openStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IllegalArgumentException("\"$name\" could not be read as an image.")
        val scaled = downscale(decoded)
        if (scaled !== decoded) decoded.recycle()

        val target = File(dir, "$id.$IMAGE_EXTENSION")
        val written = runCatching {
            target.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        }.getOrDefault(false)
        scaled.recycle()
        if (!written || target.length() == 0L) {
            target.delete()
            throw IllegalStateException("\"$name\" could not be saved as an image.")
        }
        return Attachment(
            name = name,
            mimeType = IMAGE_MIME,
            kind = KIND_IMAGE,
            path = target.absolutePath,
            sizeBytes = target.length(),
        )
    }

    private fun storeText(uri: Uri, id: String, name: String, mime: String): Attachment {
        val bytes = openStream(uri).use { readCapped(it, MAX_TEXT_BYTES) }
        if (bytes.isEmpty()) throw IllegalArgumentException("\"$name\" is empty.")
        val target = File(dir, "$id.${extensionOf(name).ifEmpty { "txt" }}")
        target.writeBytes(bytes)
        return Attachment(
            name = name,
            mimeType = mime.ifBlank { "text/plain" },
            kind = KIND_TEXT,
            path = target.absolutePath,
            sizeBytes = bytes.size.toLong(),
        )
    }

    private fun storeFile(uri: Uri, id: String, name: String, mime: String): Attachment {
        // One byte past the cap tells us it does not fit without buffering the whole document.
        val bytes = openStream(uri).use { readCapped(it, MAX_FILE_BYTES + 1) }
        if (bytes.size > MAX_FILE_BYTES) {
            throw IllegalArgumentException("\"$name\" is larger than ${MAX_FILE_BYTES / MEGABYTE} MB.")
        }
        if (bytes.isEmpty()) throw IllegalArgumentException("\"$name\" is empty.")
        val target = File(dir, "$id.${extensionOf(name).ifEmpty { "bin" }}")
        target.writeBytes(bytes)
        return Attachment(
            name = name,
            mimeType = mime.ifBlank { "application/octet-stream" },
            kind = KIND_FILE,
            path = target.absolutePath,
            sizeBytes = bytes.size.toLong(),
        )
    }

    private fun resolveName(uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
        }.getOrNull()
        return fromProvider?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: FALLBACK_NAME
    }

    private fun openStream(uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri)
            ?: throw IOException("The selected file could not be opened.")

    private fun readCapped(stream: InputStream, limit: Int): ByteArray {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val out = ByteArrayOutputStream()
        while (out.size() < limit) {
            val read = stream.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read <= 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun encode(attachment: Attachment, limitBytes: Int): String? {
        val file = storedFile(attachment) ?: return null
        if (file.length() > limitBytes) return null
        return runCatching { Base64.encodeToString(file.readBytes(), Base64.NO_WRAP) }.getOrNull()
    }

    private fun storedFile(attachment: Attachment): File? =
        File(attachment.path).takeIf { it.isFile }

    /** Power of two close to the size the image needs, so the sampled bitmap is never huge. */
    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (max(width, height) / (sample * 2) >= MAX_IMAGE_DIMENSION) sample *= 2
        return sample
    }

    private fun downscale(bitmap: Bitmap): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= MAX_IMAGE_DIMENSION) return bitmap
        val ratio = MAX_IMAGE_DIMENSION.toDouble() / longest
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun isImage(mime: String, name: String): Boolean =
        mime.startsWith("image/") || extensionOf(name) in IMAGE_EXTENSIONS

    private fun isText(mime: String, name: String): Boolean {
        val lower = mime.lowercase()
        if (lower.startsWith("text/")) return true
        if (TEXT_MIME_HINTS.any { lower.contains(it) }) return true
        return extensionOf(name) in TEXT_EXTENSIONS
    }

    private fun extensionOf(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return if (extension.length in 1..8 && extension.all { it.isLetterOrDigit() }) extension else ""
    }

    companion object {
        const val KIND_IMAGE = "image"
        const val KIND_TEXT = "text"
        const val KIND_FILE = "file"

        const val MAX_IMAGES_PER_MESSAGE = 6
        const val MAX_IMAGE_DIMENSION = 1024
        const val MAX_FILE_BYTES = 8 * 1024 * 1024

        /** Characters of an attached text document that travel inside one request. */
        const val TEXT_INLINE_LIMIT = 8_000

        private const val DIRECTORY = "attachments"
        private const val MAX_TEXT_BYTES = 2 * 1024 * 1024
        private const val MEGABYTE = 1024 * 1024
        private const val JPEG_QUALITY = 82
        private const val IMAGE_MIME = "image/jpeg"
        private const val IMAGE_EXTENSION = "jpg"
        private const val FALLBACK_NAME = "attachment"
        private const val UTF8_MAX_BYTES = 4

        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic")

        private val TEXT_EXTENSIONS = setOf(
            "txt", "md", "csv", "json", "xml", "kt", "java", "py", "js", "ts", "html", "log", "yaml", "yml",
        )

        private val TEXT_MIME_HINTS = listOf(
            "application/json", "+json", "xml", "csv", "yaml", "markdown", "javascript", "x-sh", "srt",
        )

        /** Unique, filesystem-safe stem for a stored attachment. */
        fun newId(): String =
            "a" + System.currentTimeMillis().toString(36) + Random.nextInt(0x10000).toString(36)
    }
}
