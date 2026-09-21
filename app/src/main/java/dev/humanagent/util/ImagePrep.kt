package dev.humanagent.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Prepares a picked image for the model: straight from disk, downscaled so a phone photo does not
 * cost a megabyte of base64, and returned in the form OpenAI-compatible endpoints expect.
 */
object ImagePrep {

    const val MAX_DIMENSION = 1024
    private const val QUALITY = 70

    /** Returns base64 JPEG data for [path], or null when the file cannot be decoded. */
    fun encodeJpegBase64(path: String, maxDimension: Int = MAX_DIMENSION): String? {
        val original = runCatching { BitmapFactory.decodeFile(path) }.getOrNull() ?: return null
        val prepared = downscale(original, maxDimension)
        return try {
            ByteArrayOutputStream().use { buffer ->
                prepared.compress(Bitmap.CompressFormat.JPEG, QUALITY, buffer)
                Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            null
        } finally {
            if (prepared !== original) prepared.recycle()
            original.recycle()
        }
    }

    /** Base64 payloads for a whole attachment list, skipping anything unreadable. */
    fun encodeAll(paths: List<String>): List<String> =
        paths.mapNotNull { path -> runCatching { encodeJpegBase64(path) }.getOrNull() }

    /** Copies a picture into the app's own storage so it survives the picker's temporary grant. */
    fun copyInto(
        sourcePath: String,
        destination: java.io.File,
        maxDimension: Int = 1600,
    ): String? = runCatching {
        val original = BitmapFactory.decodeFile(sourcePath) ?: return@runCatching null
        val prepared = downscale(original, maxDimension)
        destination.parentFile?.mkdirs()
        java.io.FileOutputStream(destination).use { out ->
            prepared.compress(Bitmap.CompressFormat.JPEG, 88, out)
        }
        if (prepared !== original) prepared.recycle()
        original.recycle()
        destination.absolutePath
    }.getOrNull()

    private fun downscale(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxDimension) return bitmap
        val ratio = maxDimension.toFloat() / longest.toFloat()
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }
}
