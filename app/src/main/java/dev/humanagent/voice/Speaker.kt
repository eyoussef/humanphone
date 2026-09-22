package dev.humanagent.voice

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single [Handler] of this file. `TextToSpeech` is main-thread only, so every call into it is
 * marshalled onto the main looper through it.
 */
private val speechHandler = Handler(Looper.getMainLooper())

/** Runs [block] on the main thread, inline when the caller is already on it. */
private fun onMainThread(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block() else speechHandler.post(block)
}

/**
 * Voice output built on [TextToSpeech]. [say] flushes whatever is currently being spoken and
 * [speaking] tracks the engine's own progress reports. If no usable TTS engine is present the
 * speaker degrades to a silent no-op instead of failing the caller.
 *
 * [setLanguage] picks the voice to speak with, and [availableLanguages] lists the tags this device
 * can actually render. All public members may be called from any thread; engine work always happens
 * on the main thread.
 */
class Speaker(private val context: Context) : TextToSpeech.OnInitListener {

    private val _speaking = MutableStateFlow(false)

    /** True while the engine is actually rendering an utterance. */
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val _language = MutableStateFlow("")

    /** BCP-47 tag the engine is asked to speak; empty means the device default. */
    val language: StateFlow<String> = _language.asStateFlow()

    private val _availableLanguages = MutableStateFlow<List<String>>(emptyList())

    private var engine: TextToSpeech? = null
    private var ready = false
    private var available = true
    private var pending: String? = null
    private var speechRate = DEFAULT_SPEECH_RATE
    private var pitch = DEFAULT_PITCH

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

    /**
     * Chooses the voice to speak with. The tag is BCP-47 and an empty tag means the device default.
     * A tag set before the engine finished initialising is applied in [onInit]; a tag the engine
     * cannot resolve or does not support falls back to [Locale.US].
     */
    fun setLanguage(tag: String) {
        onMainThread {
            _language.value = tag
            if (ready) applyLanguage()
        }
    }

    /**
     * Sets how fast replies are spoken; 1.0 is the engine's normal speed. Values outside
     * [MIN_SPEECH_RATE]..[MAX_SPEECH_RATE] are clamped, and an engine that is still initialising
     * picks the value up in [onInit].
     */
    fun setSpeechRate(rate: Float) {
        onMainThread {
            speechRate = rate.coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE)
            if (ready) runCatching { engine?.setSpeechRate(speechRate) }
        }
    }

    /**
     * Sets the pitch of the voice; 1.0 is the engine's normal pitch. Values outside
     * [MIN_PITCH]..[MAX_PITCH] are clamped.
     */
    fun setPitch(value: Float) {
        onMainThread {
            pitch = value.coerceIn(MIN_PITCH, MAX_PITCH)
            if (ready) runCatching { engine?.setPitch(pitch) }
        }
    }

    /**
     * BCP-47 tags this engine reports it can speak, sorted and without duplicates. The list is
     * cached: it is filled once the engine is up and only re-queried by [refreshLanguages], because
     * every entry costs a round-trip to the speech service. It is empty while the engine is still
     * initialising or when there is no engine at all.
     */
    fun availableLanguages(): List<String> = _availableLanguages.value

    /** Same list as [availableLanguages], as state so a settings screen can follow it. */
    val availableLanguagesFlow: StateFlow<List<String>> = _availableLanguages.asStateFlow()

    /** Re-queries the engine, for example after the user installed another voice. */
    fun refreshLanguages() {
        onMainThread { speechHandler.post { _availableLanguages.value = queryAvailableLanguages() } }
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
            // The language may have been picked before the engine came up.
            applyLanguage()
            applyTimbre(active)
            ready = true
            // The installed voices are listed once the engine is up, away from the first frames.
            refreshLanguages()
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
            active.speak(utterance, TextToSpeech.QUEUE_FLUSH, Bundle(), UTTERANCE_ID)
        } catch (e: IllegalStateException) {
            TextToSpeech.ERROR
        }
        _speaking.value = result != TextToSpeech.ERROR
    }

    /** Pushes [_language] into a live engine, falling back to [Locale.US] when that is refused. */
    private fun applyLanguage() {
        val active = engine ?: return
        val requested = resolveLocale(_language.value)
        if (!acceptLanguage(active, requested) && requested != Locale.US) {
            acceptLanguage(active, Locale.US)
        }
    }

    /** Pushes the chosen speed and pitch into a live engine. */
    private fun applyTimbre(active: TextToSpeech) {
        runCatching { active.setSpeechRate(speechRate) }
        runCatching { active.setPitch(pitch) }
    }

    /** True when the engine accepted [locale] for speaking. */
    private fun acceptLanguage(active: TextToSpeech, locale: Locale): Boolean = try {
        when (active.setLanguage(locale)) {
            TextToSpeech.LANG_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> true

            else -> false
        }
    } catch (e: IllegalArgumentException) {
        false
    } catch (e: IllegalStateException) {
        false
    }

    /** Turns a BCP-47 tag into a usable [Locale]; anything unresolvable becomes [Locale.US]. */
    private fun resolveLocale(tag: String): Locale {
        if (tag.isBlank()) return Locale.US
        val parsed = runCatching { Locale.forLanguageTag(tag) }.getOrNull()
        return if (parsed == null || parsed.language.isBlank()) Locale.US else parsed
    }

    private fun queryAvailableLanguages(): List<String> {
        val active = engine ?: return emptyList()
        if (!ready || !available) return emptyList()
        return Locale.getAvailableLocales()
            .asSequence()
            .filter { it.language.isNotBlank() }
            .filter { locale -> isSpeakable(active, locale) }
            .mapNotNull { locale -> runCatching { locale.toLanguageTag() }.getOrNull() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .toList()
    }

    /** True when [TextToSpeech.isLanguageAvailable] reports this engine can speak [locale]. */
    private fun isSpeakable(active: TextToSpeech, locale: Locale): Boolean = try {
        when (active.isLanguageAvailable(locale)) {
            TextToSpeech.LANG_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> true

            else -> false
        }
    } catch (e: RuntimeException) {
        false
    }

    private companion object {
        const val UTTERANCE_ID = "humanphone-speech"
        const val DEFAULT_SPEECH_RATE = 1.0f
        const val DEFAULT_PITCH = 1.0f
        const val MIN_SPEECH_RATE = 0.5f
        const val MAX_SPEECH_RATE = 2.0f
        const val MIN_PITCH = 0.5f
        const val MAX_PITCH = 2.0f
    }
}
