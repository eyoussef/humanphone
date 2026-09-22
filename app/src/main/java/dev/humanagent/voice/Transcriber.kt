package dev.humanagent.voice

import dev.humanagent.llm.LlmClient
import dev.humanagent.llm.headerSafe
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody

/**
 * Where a recorded voice note is turned into words: an OpenAI-compatible endpoint, off while
 * [baseUrl] is empty so a phone that only has a chat model keeps working without it.
 */
data class SttConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val language: String = "",
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank()

    /** Endpoint the upload goes to, tolerating a base URL with or without the version and path. */
    val transcriptionUrl: String
        get() {
            val trimmed = baseUrl.trimEnd('/')
            return if (trimmed.endsWith(TRANSCRIPTION_PATH)) trimmed else trimmed + TRANSCRIPTION_PATH
        }

    private companion object {
        const val TRANSCRIPTION_PATH = "/audio/transcriptions"
    }
}

/**
 * Uploads a recording to an OpenAI-compatible `/audio/transcriptions` endpoint and returns the
 * transcript. Works against OpenAI, Groq and a whisper server running on the phone itself; the API
 * key is optional, so a local server needs no credentials.
 */
class Transcriber(private val http: OkHttpClient = LlmClient.sharedClient) {

    /** What [file] says, or the reason the endpoint would not read it. */
    suspend fun transcribe(config: SttConfig, file: File): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(upload(config, file)).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("HTTP ${response.code}: ${body.take(300)}")
                parseTranscript(body) ?: error("the answer held no transcript: ${body.take(300)}")
            }
        }
    }

    /** One multipart POST: the recording, the model, and the spoken language when it is known. */
    internal fun upload(config: SttConfig, file: File): Request {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody(RECORDING_TYPE))
            .addFormDataPart("model", config.model)
            .apply {
                if (config.language.isNotBlank()) addFormDataPart("language", config.language)
            }
            .build()
        val builder = Request.Builder().url(config.transcriptionUrl).post(body)
        val apiKey = config.apiKey.headerSafe()
        if (apiKey.isNotEmpty()) builder.header("Authorization", "Bearer $apiKey")
        return builder.build()
    }

    companion object {
        /** Voice notes are AAC inside an MP4 container, which every whisper endpoint accepts. */
        private val RECORDING_TYPE = "audio/mp4".toMediaType()

        private val json = Json { ignoreUnknownKeys = true }

        /** The `text` field of the endpoint's answer; null when the body is not a transcript. */
        internal fun parseTranscript(body: String): String? = runCatching {
            json.parseToJsonElement(body).jsonObject["text"]?.jsonPrimitive?.content
                ?.replace(WHITESPACE, " ")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()

        private val WHITESPACE = Regex("\\s+")
    }
}
