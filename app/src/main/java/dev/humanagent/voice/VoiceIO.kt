package dev.humanagent.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 * Microphone input built on [SpeechRecognizer].
 *
 * Call [startListening] when the user wants to talk; [heard] then emits each finished utterance and
 * [partial] mirrors the live hypothesis while the recognizer is working. A session that ends with
 * silence or an unrecognised phrase is restarted automatically for as long as the caller asked to
 * listen, so a hands-free conversation does not stall on the first quiet moment.
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

    private val _heard = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Completed utterances in order. Nothing is replayed, so collect this live. */
    val heard: Flow<String> = _heard

    private var recognizer: SpeechRecognizer? = null
    private var listeningRequested = false
    private var restart: Runnable? = null

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
            listeningRequested = true
            cancelRestart()
            beginListening()
        }
    }

    /** Stops the active session (and any pending auto-restart) and clears the live hypothesis. */
    fun stopListening() {
        onMainThread {
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

    /** Releases the recognizer. This instance cannot be used afterwards. */
    fun destroy() {
        onMainThread {
            listeningRequested = false
            cancelRestart()
            _partial.value = ""
            _isListening.value = false
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
    }
}

/**
 * Voice output built on [TextToSpeech]. [say] flushes whatever is currently being spoken and
 * [speaking] tracks the engine's own progress reports. If no usable TTS engine is present the
 * speaker degrades to a silent no-op instead of failing the caller.
 */
class Speaker(private val context: Context) : TextToSpeech.OnInitListener {

    private val _speaking = MutableStateFlow(false)

    /** True while the engine is actually rendering an utterance. */
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private var engine: TextToSpeech? = null
    private var ready = false
    private var available = true
    private var pending: String? = null

    /** Engines throw on utterances longer than this, so a long reply is cut down to fit. */
    private val maxUtteranceChars = TextToSpeech.getMaxSpeechInputLength()

    private val progress = object : UtteranceProgressListener() {

        override fun onStart(utteranceId: String?) {
            _speaking.value = true
        }

        override fun onDone(utteranceId: String?) {
            _speaking.value = false
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            _speaking.value = false
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            _speaking.value = false
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            _speaking.value = false
        }
    }

    init {
        // Building the engine touches the TTS service, so it starts on the main thread.
        onMainThread { startEngine() }
    }

    /**
     * Speaks [text], replacing anything already queued. Blank text is ignored. Text handed over
     * before the engine finished initialising is spoken as soon as it is ready.
     */
    fun say(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        onMainThread {
            if (!available) return@onMainThread
            if (ready) {
                speakNow(clean)
            } else {
                startEngine()
                if (!available) return@onMainThread
                pending = clean
            }
        }
    }

    /** Stops playback and shuts the engine down. [say] is a no-op afterwards. */
    fun destroy() {
        onMainThread {
            available = false
            ready = false
            pending = null
            _speaking.value = false
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
        }
    }

    override fun onInit(status: Int) {
        onMainThread {
            val active = engine
            if (status != TextToSpeech.SUCCESS || active == null) {
                available = false
                ready = false
                pending = null
                _speaking.value = false
                return@onMainThread
            }
            active.setOnUtteranceProgressListener(progress)
            active.setLanguage(Locale.US)
            active.setSpeechRate(SPEECH_RATE)
            ready = true
            val queued = pending
            pending = null
            if (queued != null) speakNow(queued)
        }
    }

    private fun startEngine() {
        if (engine != null || !available) return
        try {
            engine = TextToSpeech(context, this)
        } catch (e: Exception) {
            available = false
        }
    }

    private fun speakNow(text: String) {
        val active = engine ?: return
        val utterance = if (text.length > maxUtteranceChars) text.take(maxUtteranceChars) else text
        val result = try {
            active.speak(utterance, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        } catch (e: IllegalStateException) {
            TextToSpeech.ERROR
        }
        _speaking.value = result != TextToSpeech.ERROR
    }

    private companion object {
        const val UTTERANCE_ID = "humanphone-speech"
        const val SPEECH_RATE = 1.0f
    }
}
