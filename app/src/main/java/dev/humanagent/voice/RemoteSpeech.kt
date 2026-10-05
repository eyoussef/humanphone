package dev.humanagent.voice

import dev.humanagent.llm.LlmClient
import dev.humanagent.llm.TtsMode
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Where a spoken reply comes from: the phone's own [android.speech.tts.TextToSpeech] voice
 * ([TtsMode.LOCAL]) or an OpenAI-compatible `/audio/speech` endpoint ([TtsMode.REMOTE]) such as
 * OpenAI, Groq or a local speech server. The defaults describe the local path, so a settings file
 * written before the voice choice existed keeps working.
 */
data class TtsConfig(
    val mode: TtsMode = TtsMode.LOCAL,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val voice: String = "",
    /** BCP-47 speech language for the on-device voice; empty follows the system default. */
    val language: String = "",
    /** Package of the on-device TTS engine to speak with; empty uses the system default. */
    val enginePackage: String = "",
) {

    /** True when a remote speech request can actually be attempted with this configuration. */
    val isRemoteUsable: Boolean
        get() = mode == TtsMode.REMOTE && baseUrl.isNotBlank() && model.isNotBlank()

    /**
     * Endpoint the text is posted to. A base URL that already names the speech path is kept as it
     * is, so pasting the full endpoint works as well as pasting the API root.
     */
    val speechUrl: String
        get() {
            val root = baseUrl.trim().trimEnd('/')
            return if (root.endsWith(SPEECH_PATH)) root else root + SPEECH_PATH
        }

    private companion object {
        const val SPEECH_PATH = "/audio/speech"
    }
}

/**
 * BCP-47 tag of the speech language as a [Locale] for [android.speech.tts.TextToSpeech.setLanguage];
 * an empty tag falls back to [fallback].
 */
internal fun ttsLocale(languageTag: String, fallback: Locale = Locale.getDefault()): Locale {
    val trimmed = languageTag.trim()
    if (trimmed.isEmpty()) return fallback
    return Locale.forLanguageTag(trimmed)
}

/**
 * Fetches one spoken utterance from an OpenAI-compatible `/audio/speech` endpoint and stores the
 * returned audio in a file the player can stream from. Works against OpenAI, Groq and a local
 * speech server; the API key is optional so a local server needs no credentials.
 */
class RemoteSpeechFetcher(private val http: OkHttpClient = LlmClient.sharedClient) {

    private val client: OkHttpClient = http.newBuilder()
        .callTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * Fetches [text] and writes the audio into [out]. Every failure comes back as
     * [Result.failure] with a readable sentence; this member never throws.
     */
    suspend fun audio(config: TtsConfig, text: String, out: File): Result<File> = withContext(Dispatchers.IO) {
        if (config.model.isBlank()) {
            return@withContext Result.failure(IllegalStateException("No speech model is configured."))
        }
        val spoken = text.take(MAX_INPUT_CHARS)
        val body = buildJsonObject {
            put("model", config.model)
            put("input", spoken)
            put("voice", config.voice.ifBlank { DEFAULT_VOICE })
            put("response_format", AUDIO_FORMAT)
        }
        val request = Request.Builder()
            .url(config.speechUrl)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .apply {
                if (config.apiKey.isNotBlank()) header("Authorization", "Bearer ${config.apiKey}")
            }
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.byteStream()
                    ?: return@use Result.failure<File>(IllegalStateException("HTTP ${response.code}: empty body"))
                if (!response.isSuccessful) {
                    val snippet = readSnippet(raw)
                    return@use Result.failure<File>(IllegalStateException("HTTP ${response.code}: $snippet"))
                }
                val bytes = readCapped(raw, MAX_AUDIO_BYTES + 1)
                if (bytes.size > MAX_AUDIO_BYTES) {
                    return@use Result.failure<File>(
                        IllegalStateException("The speech is larger than ${MAX_AUDIO_BYTES / 1_000_000} MB."),
                    )
                }
                val type = response.header("Content-Type").orEmpty().lowercase()
                if (!type.startsWith("audio/") && !type.contains("octet-stream")) {
                    return@use Result.failure<File>(
                        IllegalStateException(
                            "The server answered ${type.ifBlank { "without a content type" }}, not audio: " +
                                snippetOf(bytes),
                        ),
                    )
                }
                if (bytes.isEmpty()) {
                    return@use Result.failure<File>(IllegalStateException("The server returned empty audio."))
                }
                val parent = out.parentFile
                if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                    return@use Result.failure<File>(IllegalStateException("Could not store the speech."))
                }
                val written = runCatching { out.writeBytes(bytes) }
                if (written.isFailure) {
                    return@use Result.failure<File>(
                        IllegalStateException("Could not store the speech: ${written.exceptionOrNull()}"),
                    )
                }
                Result.success(out)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Reads at most [limit] bytes from [stream], so an unwieldy answer never lands whole. */
    private fun readCapped(stream: InputStream, limit: Int): ByteArray {
        val out = ByteArray(limit)
        var read = 0
        while (read < limit) {
            val chunk = stream.read(out, read, limit - read)
            if (chunk <= 0) break
            read += chunk
        }
        return if (read == limit) out else out.copyOf(read)
    }

    private fun readSnippet(stream: InputStream): String =
        snippetOf(runCatching { stream.readBytes() }.getOrDefault(ByteArray(0)))

    private fun snippetOf(bytes: ByteArray): String =
        bytes.decodeToString().trim().replace(Regex("\\s+"), " ").take(MAX_ERROR_CHARS)

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val DEFAULT_VOICE = "alloy"
        const val AUDIO_FORMAT = "mp3"

        /** The largest utterance the speech endpoints accept (OpenAI's documented limit). */
        const val MAX_INPUT_CHARS = 4_096

        /** A spoken reply of a few minutes still fits; more than this means something else arrived. */
        const val MAX_AUDIO_BYTES = 20 * 1024 * 1024

        const val MAX_ERROR_CHARS = 300
        const val FETCH_TIMEOUT_SECONDS = 120L
    }
}