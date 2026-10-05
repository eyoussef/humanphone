package dev.humanagent.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import dev.humanagent.llm.SettingsStore
import dev.humanagent.llm.TtsMode
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * The single [Handler] of this file. `SpeechRecognizer` and `TextToSpeech` are main-thread only, so
 * every call into them is marshalled onto the main looper through it.
 */
private val speechHandler = Handler(Looper.getMainLooper())

/** Runs [block] on the main thread, inline when the caller is already on it. */
private fun onMainThread(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block() else speechHandler.post(block)
}

/**
 * Microphone input for dictation and agent commands.
 *
 * The transcript comes from one of two backends. By default the phone's own [SpeechRecognizer] is
 * used: call [startListening] when the user wants to talk; [heard] then emits each finished utterance
 * and [partial] mirrors the live hypothesis while the recognizer is working. A session that ends with
 * silence or an unrecognised phrase is restarted automatically for as long as the caller asked to
 * listen, so a hands-free conversation does not stall on the first quiet moment.
 *
 * When the configured [SttConfig] names a remote endpoint ([SttConfig.isRemoteUsable]) the microphone
 * is recorded to a WAV instead and uploaded for transcription, which is where [status] reports the
 * progress of the capture and the upload. Misconfiguration is reported through [status] rather than
 * silently falling back to the on-device recognizer.
 *
 * All public members may be called from any thread; recognizer work always happens on the main thread.
 */
class VoiceIO(private val context: Context) {

    private val _isListening = MutableStateFlow(false)

    /** True from [startListening] until recognition completes, fails or [stopListening] is called. */
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _partial = MutableStateFlow("")

    /** Live hypothesis for the utterance in progress; empty when nothing is being heard. */
    val partial: StateFlow<String> = _partial.asStateFlow()

    private val _status = MutableStateFlow("")

    /**
     * Human-readable state of the input row: empty while idle, `"Recording… 3s"` while capturing,
     * `"Transcribing…"` while uploading, and the error sentence for a few seconds after a failure.
     */
    val status: StateFlow<String> = _status.asStateFlow()

    private val _heard = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Completed utterances in order. Nothing is replayed, so collect this live. */
    val heard: Flow<String> = _heard

    @Volatile
    private var config = SttConfig()

    private val recorder = AudioRecorder(context)
    private val transcriber = RemoteTranscriber()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var capture: RemoteCapture? = null
    private var elapsedJob: Job? = null
    private var transcribeJob: Job? = null
    private var statusJob: Job? = null
    private var captureStartedAt = 0L

    /** One remote dictation attempt, guarded so its file is handled exactly once. */
    private class RemoteCapture {
        var handled = false
    }

    private var recognizer: SpeechRecognizer? = null
    private var listeningRequested = false
    private var restart: Runnable? = null

    /**
     * Replaces the backend used by the next [startListening] call. Safe to call from any thread and
     * while a session is running; a session already in flight keeps the configuration it started with.
     */
    fun updateConfig(config: SttConfig) {
        this.config = config
    }

    private val listener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            _partial.value = ""
            _isListening.value = true
        }

        // Speech has started; the level meter is not part of the UI contract.
        override fun onBeginningOfSpeech() {}

        // Raw volume changes are not surfaced anywhere.
        override fun onRmsChanged(rmsdB: Float) {}

        // Recorded audio is not consumed; only the transcript matters.
        override fun onBufferReceived(buffer: ByteArray?) {}

        // End of speech only means "the recognizer stopped hearing words"; it keeps working
        // towards onResults/onError, so the listening state stays as it is.
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            _partial.value = ""
            _isListening.value = false
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                scheduleRestart()
            }
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.filter { it.isNotBlank() }
                ?.joinToString(" ")
                .orEmpty()
            _partial.value = ""
            _isListening.value = false
            if (text.isNotEmpty()) _heard.tryEmit(text)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            _partial.value = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull { it.isNotBlank() }
                .orEmpty()
        }

        // Recognizer-specific events (language switches, hotword matches) are not used.
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /** Whether the phone has a speech recogniser the app can drive, shown in Settings. */
    fun sttStatus(): SttStatus = SttStatus(recognitionAvailable = SpeechRecognizer.isRecognitionAvailable(context))

    /**
     * Starts a recognition session. Without the RECORD_AUDIO permission this is a no-op and
     * [isListening] is forced to false rather than letting the recognizer throw.
     */
    fun startListening() {
        onMainThread {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                listeningRequested = false
                cancelRestart()
                _partial.value = ""
                _isListening.value = false
                return@onMainThread
            }
            if (config.isRemoteUsable) {
                beginRemoteListening()
                return@onMainThread
            }
            listeningRequested = true
            cancelRestart()
            beginListening()
        }
    }

    /**
     * Stops the active session (and any pending auto-restart) and clears the live hypothesis. A
     * recording in progress is finished and transcribed rather than thrown away; an upload that is
     * already running is left to complete.
     */
    fun stopListening() {
        onMainThread {
            capture?.let {
                finishCapture(it)
                return@onMainThread
            }
            listeningRequested = false
            cancelRestart()
            _partial.value = ""
            _isListening.value = false
            val active = recognizer ?: return@onMainThread
            try {
                active.cancel()
            } catch (e: IllegalStateException) {
                // The recognizer was already torn down; there is nothing left to cancel.
            }
        }
    }

    /** Releases the recognizer and any recording. This instance cannot be used afterwards. */
    fun destroy() {
        onMainThread {
            listeningRequested = false
            cancelRestart()
            capture = null
            elapsedJob?.cancel()
            elapsedJob = null
            transcribeJob?.cancel()
            transcribeJob = null
            cancelStatusJob()
            runCatching { recorder.cancel() }
            scope.cancel()
            _partial.value = ""
            _isListening.value = false
            _status.value = ""
            val active = recognizer
            recognizer = null
            if (active != null) {
                try {
                    active.cancel()
                } catch (e: IllegalStateException) {
                    // Already released.
                }
                try {
                    active.destroy()
                } catch (e: IllegalStateException) {
                    // Already released.
                }
            }
        }
    }

    /**
     * Starts a remote dictation: record audio, then upload it. A capture or upload already in flight
     * is left alone, so a second tap cannot lose the audio of the first.
     */
    private fun beginRemoteListening() {
        if (capture != null) return
        cancelRestart()
        listeningRequested = false
        _partial.value = ""
        clearStatus()
        val attempt = RemoteCapture()
        val started = runCatching {
            recorder.start { file -> scope.launch { finishCapture(attempt, file) } }
        }.getOrDefault(false)
        if (!started) {
            _isListening.value = false
            _status.value = MICROPHONE_UNAVAILABLE
            cancelStatusJob()
            statusJob = scope.launch {
                delay(STATUS_CLEAR_MS)
                if (_status.value == MICROPHONE_UNAVAILABLE) _status.value = ""
            }
            return
        }
        capture = attempt
        captureStartedAt = SystemClock.elapsedRealtime()
        _isListening.value = true
        _status.value = elapsedLabel(0)
        elapsedJob = scope.launch {
            while (isActive && capture === attempt) {
                delay(ELAPSED_TICK_MS)
                if (capture !== attempt) break
                val seconds = (SystemClock.elapsedRealtime() - captureStartedAt) / 1_000
                _status.value = elapsedLabel(seconds)
            }
        }
    }

    /** Ends the capture started by [stopListening] and hands its file over for transcription. */
    private fun finishCapture(attempt: RemoteCapture) {
        val file = runCatching { recorder.stop() }.getOrNull()
        finishCapture(attempt, file)
    }

    /** Resolves one capture attempt exactly once, whichever trigger got there first. */
    private fun finishCapture(attempt: RemoteCapture, file: File?) {
        if (attempt.handled) return
        attempt.handled = true
        elapsedJob?.cancel()
        elapsedJob = null
        _isListening.value = false
        _partial.value = ""
        if (file == null) {
            if (capture === attempt) capture = null
            clearStatus()
            return
        }
        clearStatus()
        _status.value = TRANSCRIBING
        transcribeJob = scope.launch {
            val result = runCatching { transcriber.transcribe(config, file) }
                .getOrElse { Result.failure(it) }
            runCatching { file.delete() }
            if (capture === attempt) capture = null
            result.fold(
                onSuccess = { text ->
                    clearStatus()
                    if (text.isNotBlank()) _heard.tryEmit(text)
                },
                onFailure = { error -> showError(error) },
            )
        }
    }

    /** Shows a failure sentence for a few seconds, then returns the row to its idle state. */
    private fun showError(error: Throwable) {
        val message = error.message?.takeIf { it.isNotBlank() } ?: error.toString()
        cancelStatusJob()
        _status.value = message
        statusJob = scope.launch {
            delay(STATUS_CLEAR_MS)
            if (_status.value == message) _status.value = ""
        }
    }

    private fun clearStatus() {
        cancelStatusJob()
        _status.value = ""
    }

    private fun cancelStatusJob() {
        statusJob?.cancel()
        statusJob = null
    }

    private fun elapsedLabel(seconds: Long): String = "Recording… ${seconds}s"

    private fun beginListening() {
        if (!listeningRequested) return
        val active = ensureRecognizer() ?: run {
            listeningRequested = false
            _isListening.value = false
            return
        }
        _partial.value = ""
        try {
            active.startListening(buildIntent())
        } catch (e: IllegalStateException) {
            _isListening.value = false
            return
        }
        _isListening.value = true
    }

    /** Creates the recognizer once, on the main thread, and remembers it until [destroy]. */
    private fun ensureRecognizer(): SpeechRecognizer? {
        recognizer?.let { return it }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return null
        return try {
            SpeechRecognizer.createSpeechRecognizer(context).also {
                it.setRecognitionListener(listener)
                recognizer = it
            }
        } catch (e: IllegalStateException) {
            null
        }
    }

    private fun buildIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        // The configured speech language steers the recogniser too; empty follows the system setup.
        if (config.language.isNotBlank()) putExtra(RecognizerIntent.EXTRA_LANGUAGE, config.language)
    }

    private fun scheduleRestart() {
        if (!listeningRequested) return
        cancelRestart()
        val delayed = Runnable {
            restart = null
            if (listeningRequested) beginListening()
        }
        restart = delayed
        speechHandler.postDelayed(delayed, RESTART_DELAY_MS)
    }

    private fun cancelRestart() {
        restart?.let { speechHandler.removeCallbacks(it) }
        restart = null
    }

    private companion object {
        const val RESTART_DELAY_MS = 300L
        const val ELAPSED_TICK_MS = 500L
        const val STATUS_CLEAR_MS = 6_000L
        const val MICROPHONE_UNAVAILABLE = "Microphone is not available."
        const val TRANSCRIBING = "Transcribing…"
    }
}

/** What the phone's own speech features can do right now, shown in the Settings engine cards. */
data class SttStatus(val recognitionAvailable: Boolean)

data class TtsStatus(
    val engineLabel: String,
    val ready: Boolean,
    val languageTag: String,
    val languageAvailable: Boolean,
    val problem: String?,
)

/** An installed on-device TTS engine, e.g. Google Speech Services or Samsung TTS. */
data class TtsEngineEntry(val label: String, val packageName: String)

/**
 * Every installed TTS engine, sorted by label. Reads the TTS services straight from the package
 * manager, so it works even when no engine is set as the system default yet.
 */
fun installedTtsEngines(context: Context): List<TtsEngineEntry> {
    val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
    val resolved = runCatching { context.packageManager.queryIntentServices(intent, 0) }.getOrDefault(emptyList())
    return resolved.mapNotNull { info ->
        val packageName = info.serviceInfo?.packageName ?: return@mapNotNull null
        TtsEngineEntry(
            label = runCatching { info.loadLabel(context.packageManager).toString() }.getOrDefault(packageName),
            packageName = packageName,
        )
    }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
}

/**
 * Voice output: the phone's own [TextToSpeech] voice, or speech fetched from the configured
 * OpenAI-compatible `/audio/speech` endpoint and played back. [say] always replaces whatever is
 * currently playing; when the chosen engine cannot speak, the problem is reported through [error]
 * instead of failing the caller.
 */
class Speaker(
    private val context: Context,
    settingsStore: SettingsStore,
) : TextToSpeech.OnInitListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _localSpeaking = MutableStateFlow(false)
    private val _remoteSpeaking = MutableStateFlow(false)

    /** True while either engine is actually rendering an utterance. */
    val speaking: StateFlow<Boolean> = combine(_localSpeaking, _remoteSpeaking) { local, remote -> local || remote }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val _error = MutableStateFlow("")

    /** The last thing that stopped a spoken reply, or empty while the last one sounded. */
    val error: StateFlow<String> = _error.asStateFlow()

    @Volatile
    private var ttsConfig = TtsConfig()

    private var engine: TextToSpeech? = null
    private var ready = false
    private var available = true
    private var pending: String? = null

    /** The engine the active [TextToSpeech] was actually built with; empty means the system default. */
    private var currentEnginePackage = ""

    /** True once a chosen engine failed to come up and the system default was tried instead. */
    private var fallbackTried = false

    /** Why the on-device voice cannot speak with the configured language, or null while it can. */
    private var localProblem: String? = null

    private var remoteJob: Job? = null
    private var remoteToken = Any()

    /** Engines throw on utterances longer than this, so a long reply is cut down to fit. */
    private val maxUtteranceChars = TextToSpeech.getMaxSpeechInputLength()

    private val localSpeakingListener = object : UtteranceProgressListener() {

        override fun onStart(utteranceId: String?) {
            _localSpeaking.value = true
        }

        override fun onDone(utteranceId: String?) {
            _localSpeaking.value = false
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            _localSpeaking.value = false
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            _localSpeaking.value = false
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            _localSpeaking.value = false
        }
    }

    private val remote = RemoteSpeechFetcher()

    private val player = SpeechPlayer { playing -> _remoteSpeaking.value = playing }

    init {
        // Building the engine touches the TTS service, so it starts on the main thread.
        onMainThread { startEngine() }
        // Every settings emission re-points the speaker at the chosen voice engine.
        scope.launch {
            settingsStore.settings.collect { updateTtsConfig(it.toTtsConfig()) }
        }
    }

    /** Re-points the speaker at the chosen engine; safe to call from any thread. */
    fun updateTtsConfig(config: TtsConfig) {
        val engineChanged = ttsConfig.enginePackage != config.enginePackage
        ttsConfig = config
        onMainThread {
            if (ready) applyLanguage()
            // A different on-device voice was picked: rebuild so the next utterance uses it.
            if (engineChanged) rebuildEngine()
        }
    }

    /** What the on-device voice can speak right now, shown in the Settings engine card. */
    fun ttsStatus(): TtsStatus {
        val active = engine
        if (active == null || !ready) {
            return TtsStatus(
                engineLabel = "",
                ready = false,
                languageTag = ttsConfig.language,
                languageAvailable = false,
                problem = if (available) {
                    "The text-to-speech engine is still starting."
                } else {
                    "No text-to-speech engine on this phone."
                },
            )
        }
        val wanted = ttsLocale(ttsConfig.language)
        val supported = speakable(active.isLanguageAvailable(wanted))
        return TtsStatus(
            engineLabel = engineLabelOf(active),
            ready = true,
            languageTag = ttsConfig.language,
            languageAvailable = supported,
            problem = if (supported) null else "Voice data for ${wanted.displayLanguage} is not installed on the phone.",
        )
    }

    /** The human name of the default TTS engine, e.g. "Google Speech Services". */
    private fun engineLabelOf(active: TextToSpeech): String {
        val default = runCatching { active.defaultEngine }.getOrDefault("")
        return runCatching { active.engines }
            .getOrDefault(emptyList())
            .firstOrNull { it.name == default }?.label ?: default
    }

    /**
     * Speaks [text], replacing anything already playing from either engine. Blank text is ignored.
     * Text handed over before the engine finished initialising is spoken as soon as it is ready.
     */
    fun say(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        onMainThread {
            cancelRemote()
            stopLocal()
            if (ttsConfig.isRemoteUsable) {
                speakRemotely(clean)
            } else {
                if (ttsConfig.mode == TtsMode.REMOTE) {
                    fail("The API voice is not configured; speaking with the on-device voice.")
                }
                speakLocally(clean)
            }
        }
    }

    /**
     * Speaks [text] through the configured engine and reports why nothing sounded, for the test
     * button in Settings. The local engine needs a moment to come up, so it is waited for.
     */
    suspend fun probe(text: String): Result<Unit> = withContext(Dispatchers.Main) {
        val clean = text.trim().ifEmpty { "HumanPhone voice test." }
        cancelRemote()
        stopLocal()
        if (ttsConfig.isRemoteUsable) {
            val file = newSpeechFile()
            remote.audio(ttsConfig, clean, file).fold(
                onSuccess = { audio ->
                    val played = player.play(audio)
                    audio.delete()
                    if (played.isSuccess) _error.value = ""
                    played
                },
                onFailure = { failure -> Result.failure(failure) },
            )
        } else {
            if (!awaitLocalReady()) {
                return@withContext Result.failure(IllegalStateException("No text-to-speech engine on this phone."))
            }
            applyLanguage()
            localProblem?.let { return@withContext Result.failure(IllegalStateException(it)) }
            if (!speakNow(clean)) {
                return@withContext Result.failure(IllegalStateException("The phone's voice refused the utterance."))
            }
            _error.value = ""
            Result.success(Unit)
        }
    }

    /** Stops both engines and shuts them down. [say] is a no-op afterwards. */
    fun destroy() {
        onMainThread {
            available = false
            ready = false
            pending = null
            _localSpeaking.value = false
            _remoteSpeaking.value = false
            val active = engine
            engine = null
            if (active != null) {
                try {
                    active.stop()
                } catch (e: IllegalStateException) {
                    // The engine is gone already.
                }
                try {
                    active.shutdown()
                } catch (e: IllegalStateException) {
                    // The engine is gone already.
                }
            }
            player.destroy()
        }
        remoteJob?.cancel()
        remoteJob = null
        scope.cancel()
    }

    override fun onInit(status: Int) {
        onMainThread {
            val active = engine
            if (status != TextToSpeech.SUCCESS || active == null) {
                // A failing chosen engine falls back to the system default once, then gives up.
                if (!fallbackTried && !currentEnginePackage.isNullOrBlank()) {
                    fallbackTried = true
                    shutdownEngine()
                    available = true
                    startEngine()
                    return@onMainThread
                }
                available = false
                ready = false
                pending = null
                _localSpeaking.value = false
                return@onMainThread
            }
            // An init callback from an engine that was replaced meanwhile: ignore it.
            if (engine !== active) {
                runCatching { active.shutdown() }
                return@onMainThread
            }
            runCatching { active.setOnUtteranceProgressListener(localSpeakingListener) }
            runCatching { active.setSpeechRate(SPEECH_RATE) }
            ready = true
            applyLanguage()
            val queued = pending
            pending = null
            if (queued != null) speakNow(queued)
        }
    }

    private fun startEngine() {
        if (engine != null || !available) return
        currentEnginePackage = if (fallbackTried) "" else ttsConfig.enginePackage
        val chosen = currentEnginePackage
            .takeIf { it.isNotBlank() && engineInstalled(it) }
            .orEmpty()
        if (currentEnginePackage.isNotBlank() && chosen.isEmpty()) {
            // The chosen engine was uninstalled since; fall through to the system default.
            currentEnginePackage = ""
        }
        try {
            engine = if (chosen.isBlank()) {
                TextToSpeech(context, this)
            } else {
                TextToSpeech(context, this, chosen)
            }
        } catch (e: Exception) {
            available = false
        }
    }

    private fun engineInstalled(packageName: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess

    /** Stops and discards the active engine so a later [startEngine] builds a fresh one. */
    private fun shutdownEngine() {
        val active = engine
        engine = null
        ready = false
        pending = null
        _localSpeaking.value = false
        if (active != null) {
            try {
                active.stop()
            } catch (e: IllegalStateException) {
                // The engine is gone already.
            }
            try {
                active.shutdown()
            } catch (e: IllegalStateException) {
                // The engine is gone already.
            }
        }
    }

    private fun rebuildEngine() {
        fallbackTried = false
        shutdownEngine()
        available = true
        startEngine()
    }

    private fun speakLocally(text: String) {
        if (!available) {
            fail("No text-to-speech engine on this phone.")
            return
        }
        if (!ready) {
            startEngine()
            if (!available) {
                fail("No text-to-speech engine on this phone.")
                return
            }
            pending = text
            return
        }
        val problem = localProblem
        if (problem != null) fail(problem) else _error.value = ""
        if (!speakNow(text)) fail("The phone's voice refused the utterance.")
    }

    /** Applies the configured speech language; an unsupported one falls back to the system default. */
    private fun applyLanguage() {
        val active = engine ?: return
        val wanted = ttsLocale(ttsConfig.language)
        if (speakable(active.setLanguage(wanted))) {
            localProblem = null
            return
        }
        val fallback = ttsLocale("", fallback = Locale.US)
        localProblem = if (speakable(active.setLanguage(fallback))) {
            "No on-device voice for ${wanted.displayLanguage}; speaking with the ${fallback.displayLanguage} voice."
        } else {
            "No text-to-speech voice is installed on this phone."
        }
    }

    private fun speakable(result: Int): Boolean =
        result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED

    private fun speakNow(text: String): Boolean {
        val active = engine ?: return false
        val utterance = if (text.length > maxUtteranceChars) text.take(maxUtteranceChars) else text
        val result = try {
            active.speak(utterance, TextToSpeech.QUEUE_FLUSH, Bundle(), UTTERANCE_ID)
        } catch (e: IllegalStateException) {
            TextToSpeech.ERROR
        }
        _localSpeaking.value = result != TextToSpeech.ERROR
        return _localSpeaking.value
    }

    private suspend fun awaitLocalReady(): Boolean {
        if (ready) return true
        startEngine()
        val deadline = SystemClock.elapsedRealtime() + LOCAL_READY_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ready) return true
            if (!available) return false
            delay(100)
        }
        return ready
    }

    /** Fetches the utterance, then plays it; a newer [say] supersedes this one through [remoteToken]. */
    private fun speakRemotely(text: String) {
        val config = ttsConfig
        val token = Any()
        remoteToken = token
        val fetchJob = scope.launch {
            val file = newSpeechFile()
            val fetched = try {
                remote.audio(config, text, file)
            } catch (e: kotlinx.coroutines.CancellationException) {
                file.delete()
                throw e
            } catch (e: Exception) {
                Result.failure<File>(e)
            }
            if (remoteToken !== token) {
                file.delete()
                return@launch
            }
            fetched.fold(
                onSuccess = { audio ->
                    player.play(audio).fold(
                        onSuccess = { _error.value = "" },
                        onFailure = { failure -> fail("Could not play the API voice: ${reasonOf(failure)}") },
                    )
                    audio.delete()
                },
                onFailure = { failure -> fail("Could not fetch the API voice: ${reasonOf(failure)}") },
            )
        }
        remoteJob = fetchJob
    }

    /** Cancels an in-flight remote utterance and silences the player; the next [say] takes over. */
    private fun cancelRemote() {
        remoteToken = Any()
        remoteJob?.cancel()
        remoteJob = null
        player.stop()
        _remoteSpeaking.value = false
    }

    private fun stopLocal() {
        val active = engine
        if (active != null) {
            try {
                active.stop()
            } catch (e: IllegalStateException) {
                // The engine was already torn down.
            }
        }
        _localSpeaking.value = false
    }

    private fun newSpeechFile(): File = File(File(context.cacheDir, SPEECH_DIRECTORY), "speech-${System.currentTimeMillis()}.mp3")

    private fun fail(message: String) {
        _error.value = message
    }

    /** Engine failures carry a message; plain transport ones do not, so the class name stands in. */
    private fun reasonOf(failure: Throwable): String =
        failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName

    private companion object {
        const val UTTERANCE_ID = "humanphone-speech"
        const val SPEECH_RATE = 1.0f
        const val SPEECH_DIRECTORY = "voice"
        const val LOCAL_READY_TIMEOUT_MS = 5_000L
    }
}

/**
 * Plays fetched speech files with [MediaPlayer]; every call is marshalled to the main looper, the
 * same way the recognizer and the TTS engine are. A new [play] always stops the previous one.
 */
private class SpeechPlayer(private val onPlaying: (Boolean) -> Unit) {

    private var player: MediaPlayer? = null

    /** Plays [audio] and resolves the moment playback finished, failed or was stopped. */
    suspend fun play(audio: File): Result<Unit> = suspendCancellableCoroutine { continuation ->
        onMainThread {
            release()
            val fresh = try {
                MediaPlayer()
            } catch (e: Exception) {
                null
            }
            if (fresh == null) {
                if (continuation.isActive) continuation.resume(Result.failure(IllegalStateException("Could not start playback.")))
                return@onMainThread
            }
            try {
                fresh.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                fresh.setDataSource(audio.absolutePath)
            } catch (e: Exception) {
                fresh.release()
                if (continuation.isActive) continuation.resume(Result.failure(IllegalStateException("Could not read the speech.")))
                return@onMainThread
            }
            fresh.setOnPreparedListener { prepared ->
                val started = runCatching { prepared.start() }.isSuccess
                if (started) {
                    onPlaying(true)
                } else {
                    shutDown()
                    if (continuation.isActive) {
                        continuation.resume(Result.failure(IllegalStateException("Playback failed to start.")))
                    }
                }
            }
            fresh.setOnCompletionListener {
                shutDown()
                if (continuation.isActive) continuation.resume(Result.success(Unit))
            }
            fresh.setOnErrorListener { _, what, extra ->
                shutDown()
                if (continuation.isActive) {
                    continuation.resume(Result.failure(IllegalStateException("Playback failed ($what/$extra).")))
                }
                true
            }
            player = fresh
            fresh.prepareAsync()
            if (continuation.isActive) continuation.invokeOnCancellation { stop() }
        }
    }

    /** Stops playback; the caller owns the file and may reuse the player afterwards. */
    fun stop() {
        onMainThread { release() }
    }

    fun destroy() = stop()

    /** Releases the player and reports it as silent; the file stays untouched for cleanup. */
    private fun release() {
        val active = player
        player = null
        onPlaying(false)
        if (active != null) {
            try {
                active.stop()
            } catch (e: IllegalStateException) {
                // Not in a stoppable state.
            }
            active.release()
        }
    }

    private fun shutDown() {
        release()
    }
}
