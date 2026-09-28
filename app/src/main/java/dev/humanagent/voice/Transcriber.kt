package dev.humanagent.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import dev.humanagent.llm.LlmClient
import dev.humanagent.llm.SttMode
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody

/**
 * Where a dictation session gets its transcript from: the phone's own recogniser ([SttMode.DEVICE])
 * or an OpenAI-compatible `/audio/transcriptions` endpoint ([SttMode.REMOTE]).
 *
 * The defaults describe the device path, so a config built from an older settings file stays usable.
 */
data class SttConfig(
    val mode: SttMode = SttMode.DEVICE,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val language: String = "",
) {

    /** True when a remote transcription can actually be attempted with this configuration. */
    val isRemoteUsable: Boolean
        get() = mode == SttMode.REMOTE && baseUrl.isNotBlank() && model.isNotBlank()

    /**
     * Endpoint the recording is posted to. A base URL that already names the transcriptions path is
     * kept as it is, so pasting the full endpoint works as well as pasting the API root.
     */
    val transcriptionsUrl: String
        get() {
            val root = baseUrl.trim().trimEnd('/')
            return if (root.endsWith(TRANSCRIPTIONS_PATH)) root else root + TRANSCRIPTIONS_PATH
        }

    private companion object {
        const val TRANSCRIPTIONS_PATH = "/audio/transcriptions"
    }
}

/**
 * Microphone capture for remote transcription.
 *
 * [start] records 16 kHz mono PCM into the app cache and finalises a proper RIFF/WAVE file when the
 * session ends: after the caller stops it, after [SILENCE_MS] of silence following speech, after
 * [NO_SPEECH_MS] without any speech at all, or at the [HARD_CAP_MS] hard cap. The caller-supplied
 * callback fires exactly once, from the recording thread, whenever the recorder stopped the session
 * by itself; an explicit [stop] returns the file instead.
 *
 * Every public member may be called from any thread.
 */
class AudioRecorder(private val context: Context) {

    private val _recording = MutableStateFlow(false)

    /** True while samples are being captured. */
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private val _level = MutableStateFlow(0f)

    /** Smoothed input amplitude in `0f..1f`, for a live meter. */
    val level: StateFlow<Float> = _level.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var current: Session? = null

    /** One capture attempt, shared between the recording thread and whoever stops it. */
    private class Session(
        val recorder: AudioRecord,
        val pcm: File,
        val onAutoStop: (File?) -> Unit,
    ) {
        val stopRequested = AtomicBoolean(false)
        val cancelRequested = AtomicBoolean(false)
        private val finished = AtomicBoolean(false)
        private val latch = CountDownLatch(1)

        @Volatile
        var result: File? = null

        @Volatile
        var job: Job? = null

        val isFinished: Boolean
            get() = finished.get()

        /** Publishes the outcome of the session and wakes anything waiting in [await]. */
        fun complete(file: File?, autoStop: Boolean) {
            if (!finished.compareAndSet(false, true)) return
            result = file
            if (autoStop) runCatching { onAutoStop(file) }
            latch.countDown()
        }

        fun awaitResult(): File? {
            runCatching { latch.await(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            return result
        }
    }

    /**
     * Begins a capture. Returns false when the microphone is unavailable (no permission, no input
     * device) or when a previous session has not finished yet. [onAutoStop] runs on the recording
     * thread when the recorder ended the session on its own, with `null` when nothing usable was
     * captured.
     */
    @SuppressLint("MissingPermission")
    fun start(onAutoStop: (File?) -> Unit): Boolean {
        current?.let { if (!it.isFinished) return false }
        val recorder = buildRecorder() ?: return false
        val directory = File(context.cacheDir, CACHE_DIR)
        if (!directory.isDirectory && !directory.mkdirs()) {
            recorder.release()
            return false
        }
        directory.listFiles()?.forEach { it.delete() }
        val session = Session(
            recorder = recorder,
            pcm = File(directory, "capture-${SystemClock.uptimeMillis()}.pcm"),
            onAutoStop = onAutoStop,
        )
        current = session
        session.job = scope.launch { capture(session) }
        return true
    }

    /** Ends the capture and returns the finished WAV, or null when nothing usable was recorded. */
    fun stop(): File? {
        val session = current ?: return null
        session.stopRequested.set(true)
        return session.awaitResult()
    }

    /** Ends the capture and discards it; no file is produced and no callback fires. */
    fun cancel() {
        val session = current ?: return
        session.cancelRequested.set(true)
        session.awaitResult()
    }

    private fun capture(session: Session) {
        val recorder = session.recorder
        val buffer = ShortArray(CHUNK_SAMPLES)
        val bytes = ByteArray(CHUNK_SAMPLES * 2)
        var started = false
        var samples = 0L
        var speechAt = -1L
        var autoStop = false
        try {
            started = startRecording(recorder)
            if (started) {
                _recording.value = true
                val startedAt = SystemClock.elapsedRealtime()
                FileOutputStream(session.pcm).use { sink ->
                    while (!session.stopRequested.get() && !session.cancelRequested.get()) {
                        val read = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                        if (read <= 0) {
                            if (read == AudioRecord.ERROR_DEAD_OBJECT ||
                                read == AudioRecord.ERROR_INVALID_OPERATION
                            ) {
                                break
                            }
                            continue
                        }
                        val now = SystemClock.elapsedRealtime()
                        val peak = peakOf(buffer, read)
                        _level.value = _level.value * LEVEL_SMOOTHING + peak * (1f - LEVEL_SMOOTHING)
                        if (peak > NOISE_FLOOR) speechAt = now
                        encode(buffer, read, bytes)
                        sink.write(bytes, 0, read * 2)
                        samples += read
                        // Nothing but noise for too long: there is no utterance to transcribe.
                        if (speechAt < 0 && now - startedAt >= NO_SPEECH_MS) {
                            autoStop = true
                            break
                        }
                        // Speech ran out: the trailing silence ends the utterance.
                        if (speechAt > 0 && now - speechAt >= SILENCE_MS) {
                            autoStop = true
                            break
                        }
                        if (now - startedAt >= HARD_CAP_MS) {
                            autoStop = true
                            break
                        }
                    }
                }
            }
        } catch (e: IOException) {
            // The capture file became unwritable; whatever was written so far is used.
        } catch (e: IllegalStateException) {
            // The recorder was torn down underneath the loop.
        } finally {
            stopRecording(recorder)
            _recording.value = false
            _level.value = 0f
            val cancelled = session.cancelRequested.get()
            val usable = started && samples >= MIN_SAMPLES && !(autoStop && speechAt < 0)
            val file = if (cancelled || !usable) null else finalise(session.pcm)
            if (file == null) session.pcm.delete()
            session.complete(file, autoStop = autoStop && !cancelled)
        }
    }

    /** Builds the recorder, or returns null when the device cannot provide the requested input. */
    @SuppressLint("MissingPermission")
    private fun buildRecorder(): AudioRecord? = try {
        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSizeBytes())
            .build()
        if (recorder.state == AudioRecord.STATE_INITIALIZED) {
            recorder
        } else {
            recorder.release()
            null
        }
    } catch (e: Exception) {
        null
    }

    private fun startRecording(recorder: AudioRecord): Boolean = try {
        recorder.startRecording()
        recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING
    } catch (e: IllegalStateException) {
        false
    }

    private fun stopRecording(recorder: AudioRecord) {
        runCatching { recorder.stop() }
        runCatching { recorder.release() }
    }

    /** Writes the 16-bit samples of one read into [out] as little-endian bytes. */
    private fun encode(samples: ShortArray, count: Int, out: ByteArray) {
        var index = 0
        for (i in 0 until count) {
            val value = samples[i].toInt()
            out[index++] = (value and 0xFF).toByte()
            out[index++] = ((value shr 8) and 0xFF).toByte()
        }
    }

    private fun peakOf(samples: ShortArray, count: Int): Float {
        var peak = 0
        for (i in 0 until count) {
            val value = abs(samples[i].toInt())
            if (value > peak) peak = value
        }
        return peak / 32768f
    }

    /** Wraps the raw capture in a 44-byte RIFF/WAVE header and returns the playable file. */
    private fun finalise(pcm: File): File? {
        val length = pcm.length()
        if (length <= 0L) return null
        val wav = File(pcm.parentFile, pcm.nameWithoutExtension + ".wav")
        return runCatching {
            FileOutputStream(wav).use { sink ->
                sink.write(wavHeader(length, SAMPLE_RATE, CHANNELS, BITS_PER_SAMPLE))
                pcm.inputStream().use { it.copyTo(sink) }
            }
            pcm.delete()
            wav
        }.getOrNull()
    }

    private fun bufferSizeBytes(): Int {
        val min = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        return if (min > 0) max(min * 2, CHUNK_SAMPLES * 4) else CHUNK_SAMPLES * 4
    }

    private companion object {
        const val CACHE_DIR = "voice"
        const val SAMPLE_RATE = 16_000
        const val CHANNELS = 1
        const val BITS_PER_SAMPLE = 16
        const val CHUNK_SAMPLES = 1_600
        const val MIN_SAMPLES = SAMPLE_RATE * 300 / 1_000
        const val NOISE_FLOOR = 0.05f
        const val LEVEL_SMOOTHING = 0.7f
        const val SILENCE_MS = 1_200L
        const val NO_SPEECH_MS = 8_000L
        const val HARD_CAP_MS = 60_000L
        const val STOP_TIMEOUT_MS = 3_000L
    }
}

/**
 * The 44-byte RIFF/WAVE header that fronts a raw PCM capture, for the mono 16-bit format the
 * recorder produces. [pcmBytes] is the size of the payload written after the header, and the
 * header describes it exactly so the server reads the upload as a well-formed WAV.
 */
internal fun wavHeader(
    pcmBytes: Long,
    sampleRate: Int,
    channels: Int,
    bitsPerSample: Int,
): ByteArray {
    val headerBytes = 44
    val formatChunkBytes = 16
    val formatPcm = 1
    val buffer = ByteBuffer.allocate(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
    buffer.putInt((headerBytes - 8 + pcmBytes).toInt())
    buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
    buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
    buffer.putInt(formatChunkBytes)
    buffer.putShort(formatPcm.toShort())
    buffer.putShort(channels.toShort())
    buffer.putInt(sampleRate)
    buffer.putInt(sampleRate * channels * bitsPerSample / 8)
    buffer.putShort((channels * bitsPerSample / 8).toShort())
    buffer.putShort(bitsPerSample.toShort())
    buffer.put("data".toByteArray(Charsets.US_ASCII))
    buffer.putInt(pcmBytes.toInt())
    return buffer.array()
}

/**
 * Uploads a recording to an OpenAI-compatible `/audio/transcriptions` endpoint and returns the
 * transcript. Works against OpenAI, Groq and a local whisper server; the API key is optional so a
 * local server needs no credentials.
 */
class RemoteTranscriber(private val http: OkHttpClient = LlmClient.sharedClient) {

    private val client: OkHttpClient = http.newBuilder()
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    /** Posts [wav] and resolves with the transcribed text, or fails with a readable message. */
    suspend fun transcribe(config: SttConfig, wav: File): Result<String> = withContext(Dispatchers.IO) {
        if (config.model.isBlank()) {
            return@withContext Result.failure(IllegalStateException("No transcription model is configured."))
        }
        if (!wav.isFile || wav.length() == 0L) {
            return@withContext Result.failure(IllegalStateException("The recording is empty."))
        }
        try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", config.model)
                .addFormDataPart("response_format", "json")
                .apply {
                    if (config.language.isNotBlank()) addFormDataPart("language", config.language)
                }
                .addFormDataPart("file", "audio.wav", wav.asRequestBody(WAV_MEDIA_TYPE))
                .build()
            val request = Request.Builder()
                .url(config.transcriptionsUrl)
                .post(body)
                .apply {
                    if (config.apiKey.isNotBlank()) header("Authorization", "Bearer ${config.apiKey}")
                }
                .build()
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    Result.success(parseTranscript(raw).trim())
                } else {
                    val snippet = raw.trim().replace(Regex("\\s+"), " ").take(MAX_ERROR_CHARS)
                    Result.failure(IllegalStateException("HTTP ${response.code}: $snippet"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseTranscript(raw: String): String =
        runCatching { json.decodeFromString<TranscriptionResponse>(raw).text }.getOrDefault("")

    @Serializable
    private data class TranscriptionResponse(val text: String = "")

    private companion object {
        val WAV_MEDIA_TYPE = "audio/wav".toMediaType()
        const val CALL_TIMEOUT_SECONDS = 90L
        const val MAX_ERROR_CHARS = 300
    }
}
