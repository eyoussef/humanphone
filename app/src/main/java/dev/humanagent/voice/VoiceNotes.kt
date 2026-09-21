package dev.humanagent.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single [Handler] of this file. [MediaRecorder] and [MediaPlayer] are main-thread only, so
 * every call into them is marshalled onto the main looper through it.
 */
private val noteHandler = Handler(Looper.getMainLooper())

/** How long an off-main caller waits for the main looper before giving up; see [callOnMain]. */
private const val MAIN_CALL_TIMEOUT_MS = 3_000L

/** While recording, the elapsed counter and its UI refresh at this interval. */
private const val TICK_MS = 200L

/** Recorded notes live in their own directory next to the conversation history. */
private const val VOICE_DIR = "voice_notes"

/**
 * Runs [block] on the main thread and hands back its result. The call is inline when the caller is
 * already on the main thread (the case the UI hits), otherwise it is posted and awaited so that the
 * value-returning members of the recorder keep their contract. A null result means the main thread
 * never got to the call, which only happens if it is wedged.
 */
private fun <T> callOnMain(block: () -> T): T? {
    if (Looper.myLooper() == Looper.getMainLooper()) return block()
    val latch = CountDownLatch(1)
    var result: T? = null
    noteHandler.post {
        try {
            result = block()
        } finally {
            latch.countDown()
        }
    }
    return if (latch.await(MAIN_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) result else null
}

/**
 * Records a voice note with [MediaRecorder] into the app's own storage.
 *
 * Notes are AAC audio inside an MP4 container at `filesDir/voice_notes/note-<millis>.m4a`, which is
 * what every Android player can decode and what the model side turns into an attachment. [recording]
 * and [elapsedMs] drive the recording bar; [elapsedMs] is refreshed every [TICK_MS] while a note is
 * being captured. Without the microphone permission [start] refuses instead of letting the platform
 * throw, so the caller can ask for it.
 *
 * All public members may be called from any thread; the recorder itself always runs on the main thread.
 */
class VoiceNoteRecorder(private val context: Context) {

    private val _recording = MutableStateFlow(false)

    /** True between a successful [start] and [stop], [cancel] or [destroy]. */
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private val _elapsedMs = MutableStateFlow(0L)

    /** Length of the note being captured, updated every [TICK_MS]; zero when nothing is recording. */
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    private var recorder: MediaRecorder? = null
    private var output: File? = null
    private var startedAtMs = 0L
    private var destroyed = false

    private val ticker = object : Runnable {
        override fun run() {
            if (recorder == null) return
            _elapsedMs.value = SystemClock.elapsedRealtime() - startedAtMs
            noteHandler.postDelayed(this, TICK_MS)
        }
    }

    /**
     * Starts a new note. Returns false when RECORD_AUDIO has not been granted, when the storage
     * directory cannot be created, or when the recorder refuses to start; nothing is left behind in
     * that case.
     */
    fun start(): Boolean = callOnMain { startLocked() } ?: false

    /**
     * Ends the note and returns the absolute path of the finished `.m4a`, or null when nothing was
     * captured — an empty or half-written file is deleted rather than handed to the caller.
     */
    fun stop(): String? = callOnMain { finishLocked(keep = true) }

    /** Ends the note and throws the recording away. */
    fun cancel() {
        callOnMain { finishLocked(keep = false) }
    }

    /** Releases the recorder and stops the elapsed-time ticker. The instance is spent afterwards. */
    fun destroy() {
        callOnMain {
            destroyed = true
            finishLocked(keep = false)
        }
    }

    private fun startLocked(): Boolean {
        if (destroyed) return false
        if (recorder != null) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        val directory = File(context.filesDir, VOICE_DIR)
        if (!directory.isDirectory && !directory.mkdirs()) return false
        val file = File(directory, "note-${System.currentTimeMillis()}.m4a")

        val created = try {
            newRecorder()
        } catch (e: Exception) {
            return false
        }

        val started = try {
            configure(created, file)
            created.prepare()
            created.start()
            true
        } catch (e: Exception) {
            false
        }
        if (!started) {
            runCatching { created.release() }
            file.delete()
            return false
        }

        recorder = created
        output = file
        startedAtMs = SystemClock.elapsedRealtime()
        _elapsedMs.value = 0L
        _recording.value = true
        noteHandler.postDelayed(ticker, TICK_MS)
        return true
    }

    /** Stops and releases the active recorder. Returns the file only when [keep] and it is usable. */
    private fun finishLocked(keep: Boolean): String? {
        noteHandler.removeCallbacks(ticker)
        val active = recorder
        val file = output
        recorder = null
        output = null
        _recording.value = false
        _elapsedMs.value = 0L

        val captured = if (active != null) {
            // stop() throws when no frame ever reached the file, so both the throw and a short file
            // are treated as "nothing was recorded".
            val stopped = try {
                active.stop()
                true
            } catch (e: Exception) {
                false
            }
            runCatching { active.release() }
            stopped
        } else {
            false
        }

        if (file == null) return null
        val usable = captured && file.exists() && file.length() > 0L
        if (!keep || !usable) {
            file.delete()
            return null
        }
        return file.absolutePath
    }

    /** API 31 moved [MediaRecorder] to a context constructor; both shapes are used where available. */
    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()

    /** Mono AAC in an MP4 container: playable everywhere and small enough to attach to a turn. */
    private fun configure(recorder: MediaRecorder, file: File) {
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        recorder.setAudioSamplingRate(SAMPLE_RATE_HZ)
        recorder.setAudioEncodingBitRate(BIT_RATE_BPS)
        recorder.setOutputFile(file.absolutePath)
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 44_100
        const val BIT_RATE_BPS = 96_000
    }
}

/**
 * Plays one recorded note at a time with [MediaPlayer].
 *
 * [playingPath] is the note the user is hearing right now and turns null on completion, on error and
 * on [stop], which is what lets the bubble show a play or a stop button. Handing in a new path while
 * something is playing releases the old player first, so only one [MediaPlayer] is ever alive.
 *
 * All public members may be called from any thread; playback always happens on the main thread.
 */
class VoiceNotePlayer(context: Context) {

    // The context belongs to the frozen constructor surface shared with the recorder; playback is
    // addressed purely by absolute path, so nothing in here needs it.
    private val _playingPath = MutableStateFlow<String?>(null)

    /** Absolute path of the note being played, or null while nothing plays. */
    val playingPath: StateFlow<String?> = _playingPath.asStateFlow()

    private var player: MediaPlayer? = null
    private var destroyed = false

    /** Plays [path], stopping whatever was playing before. Missing or unreadable files are ignored. */
    fun play(path: String) {
        callOnMain { playLocked(path) }
    }

    /** Stops playback and releases the player. */
    fun stop() {
        callOnMain { releaseLocked() }
    }

    /** Stops playback and releases the player. The instance is spent afterwards. */
    fun destroy() {
        callOnMain {
            destroyed = true
            releaseLocked()
        }
    }

    private fun playLocked(path: String) {
        if (destroyed) return
        val file = File(path)
        if (!file.exists() || file.length() == 0L) return
        releaseLocked()

        val created = try {
            MediaPlayer().also { candidate ->
                candidate.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                candidate.setDataSource(path)
                candidate.setOnCompletionListener { releaseLocked() }
                candidate.setOnErrorListener { _, _, _ ->
                    releaseLocked()
                    true
                }
                candidate.prepare()
                candidate.start()
            }
        } catch (e: Exception) {
            releaseLocked()
            return
        }

        player = created
        _playingPath.value = path
    }

    private fun releaseLocked() {
        val active = player
        player = null
        _playingPath.value = null
        if (active != null) {
            runCatching { if (active.isPlaying) active.stop() }
            runCatching { active.release() }
        }
    }
}

/**
 * Whole seconds of [path] taken from the container metadata, or null when the file cannot be read.
 * The chat bubble shows it next to the play button instead of opening a player just to measure it.
 */
fun voiceNoteDurationSeconds(path: String): Int? = runCatching {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(path)
        val millis = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()
        if (millis == null || millis <= 0L) null else Math.round(millis / 1000.0).toInt()
    } finally {
        retriever.release()
    }
}.getOrNull()
