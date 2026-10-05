package dev.humanagent.llm

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.humanagent.voice.SttConfig
import dev.humanagent.voice.TtsConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class AppSettings(
    val providerKind: ProviderKind = ProviderKind.OLLAMA,
    val baseUrl: String = ProviderKind.OLLAMA.defaultBaseUrl,
    val apiKey: String = "",
    val model: String = ProviderKind.OLLAMA.defaultModel,
    val temperature: Double = 0.6,
    val maxTokens: Int = 2048,
    val persona: String = DEFAULT_PERSONA,
    val speakReplies: Boolean = true,
    val sendScreenshots: Boolean = false,
    val maxSteps: Int = 20,
    val stepDelayMs: Int = 600,
    val sttMode: SttMode = SttMode.DEVICE,
    val sttBaseUrl: String = "https://api.openai.com/v1",
    val sttApiKey: String = "",
    val sttModel: String = "whisper-1",
    val sttLanguage: String = "",
    /** App interface language as a BCP-47 tag; empty follows the system language. */
    val language: String = "",
    /** Auto mode: the agent watches notifications and answers the ones that need a reply. */
    val autoMode: Boolean = false,
    /**
     * Optional limits for Auto mode: what the watchdog may answer for the user and for whom,
     * e.g. "only messages from my family, in Arabic" or "only work e-mail about invoices".
     */
    val autoModePersona: String = "",
    /** How replies are spoken: the phone's own TTS voice, or an OpenAI-compatible speech endpoint. */
    val ttsMode: TtsMode = TtsMode.LOCAL,
    val ttsBaseUrl: String = "https://api.openai.com/v1",
    val ttsApiKey: String = "",
    val ttsModel: String = "gpt-4o-mini-tts",
    val ttsVoice: String = "alloy",
    /** Package of the on-device TTS engine to speak with; empty uses the system default. */
    val ttsEngine: String = "",
    /**
     * Let the assistant send texts directly when the SMS permission is granted. Off (default),
     * a send opens the messaging app with the message pre-filled and the user presses send —
     * the composer path costs one tap and needs no permission to message anyone.
     */
    val directSms: Boolean = false,
) {
    fun toProviderConfig(): ProviderConfig = ProviderConfig(
        kind = providerKind,
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        temperature = temperature,
        maxTokens = maxTokens,
    )

    /** The speech-to-text backend the next dictation session should use. */
    fun toSttConfig(): SttConfig = SttConfig(
        mode = sttMode,
        baseUrl = sttBaseUrl,
        apiKey = sttApiKey,
        model = sttModel,
        language = sttLanguage,
    )

    /** The text-to-speech backend the next spoken reply should use. */
    fun toTtsConfig(): TtsConfig = TtsConfig(
        mode = ttsMode,
        baseUrl = ttsBaseUrl,
        apiKey = ttsApiKey,
        model = ttsModel,
        voice = ttsVoice,
        language = sttLanguage,
        enginePackage = ttsEngine,
    )

    companion object {
        val DEFAULT_PERSONA =
            "You are HumanPhone, the person living inside this Android phone. You speak like a warm, " +
                "quick-witted human assistant, never like a chatbot. You keep answers short and concrete, " +
                "you act on the phone when asked instead of explaining how the user could act, and you " +
                "admit plainly when you cannot do something."
    }
}

/**
 * Which engine turns recorded audio into text: the phone's own recogniser, or an
 * OpenAI-compatible transcription endpoint such as OpenAI, Groq or a local whisper server.
 */
enum class SttMode(val label: String) {
    DEVICE("On-device"),
    REMOTE("Remote Whisper API"),
}

/**
 * Which engine speaks the replies: the phone's own [TextToSpeech] voice, or an
 * OpenAI-compatible speech endpoint such as OpenAI, Groq or a local speech server.
 */
enum class TtsMode(val label: String) {
    LOCAL("On-device"),
    REMOTE("API provider"),
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "humanphone_settings")

class SettingsStore(private val context: Context) {

    private val key = stringPreferencesKey("settings_json")

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        prefs[key]?.let { raw ->
            // The blob is a sealed box on current installs; legacy plaintext still reads.
            SecretVault.open(raw).getOrNull()?.let { payload ->
                runCatching { Json.decodeFromString<AppSettings>(payload) }.getOrNull()
            }
        } ?: AppSettings()
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs[key]?.let { raw -> SecretVault.open(raw).getOrNull() }?.let { payload ->
                runCatching { Json.decodeFromString<AppSettings>(payload) }.getOrNull()
            } ?: AppSettings()
            val plain = Json.encodeToString(transform(current))
            // Sealing is attempted on every write; a Keystore failure keeps the legacy
            // plaintext path so the user's keys are never lost to a hardware hiccup.
            prefs[key] = SecretVault.seal(plain).getOrDefault(plain)
        }
    }

    suspend fun reset() {
        context.settingsDataStore.edit { it.remove(key) }
    }
}
