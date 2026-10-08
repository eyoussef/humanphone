package dev.humanagent.brain

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** SHA-256 verification of the downloaded model. Pure file work, unit-tested. */
object Digests {

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** True when [file] hashes to [expected] (case-insensitive hex). */
    fun matches(file: File, expected: String): Boolean =
        file.exists() && file.length() > 0 && sha256(file).equals(expected.trim().lowercase(), ignoreCase = true)
}

/**
 * Downloads the model bundle once, resuming an interrupted transfer and refusing to keep a
 * single corrupted byte: the checksum must match the pinned SHA-256 before the file becomes
 * the model.
 */
class BrainDownloader(
    private val http: okhttp3.OkHttpClient = dev.humanagent.llm.LlmClient.sharedClient,
) {

    /**
     * Fetches [BrainSpec.MODEL_URL] into [target]. Progress is reported as (bytes, total).
     * Throws on network failure or checksum mismatch; [target] only ever appears complete
     * and verified.
     */
    suspend fun fetch(target: File, onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, target.name + ".part")
        if (target.exists() && Digests.matches(target, BrainSpec.MODEL_SHA256)) return@withContext
        target.delete()
        try {
            val offset = if (partial.exists()) partial.length() else 0L
            val request = okhttp3.Request.Builder()
                .url(BrainSpec.MODEL_URL)
                .apply { if (offset > 0) header("Range", "bytes=$offset-") }
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 206) {
                    throw IllegalStateException("Download failed with HTTP ${response.code}")
                }
                val body = response.body ?: throw IllegalStateException("Download failed: empty body")
                // A 200 with an offset means the server ignored Range: start over.
                val append = response.code == 206 && offset > 0
                if (!append && offset > 0) partial.delete()
                val total = body.contentLength().let { if (it >= 0) it + (if (append) offset else 0) else BrainSpec.MODEL_BYTES }
                val sink = java.io.FileOutputStream(partial, append)
                sink.use { out ->
                    val buffer = ByteArray(1 shl 16)
                    var done = if (append) offset else 0L
                    while (true) {
                        val read = body.byteStream().read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        done += read
                        onProgress(done, total)
                    }
                }
            }
            if (!Digests.matches(partial, BrainSpec.MODEL_SHA256)) {
                partial.delete()
                throw IllegalStateException("Checksum mismatch — the download was corrupted and deleted")
            }
            if (!partial.renameTo(target)) throw IllegalStateException("Could not finalize the model file")
        } catch (e: Exception) {
            // Keep a valid .part for resume; a corrupted one was already deleted above.
            throw e
        }
    }
}
