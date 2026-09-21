package dev.humanagent.llm

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
) {
    fun toProviderConfig(): ProviderConfig = ProviderConfig(
        kind = providerKind,
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        temperature = temperature,
        maxTokens = maxTokens,
    )

    companion object {
        val DEFAULT_PERSONA =
            "You are HumanPhone, the person living inside this Android phone. You speak like a warm, " +
                "quick-witted human assistant, never like a chatbot. You keep answers short and concrete, " +
                "you act on the phone when asked instead of explaining how the user could act, and you " +
                "admit plainly when you cannot do something."
    }
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "humanphone_settings")

class SettingsStore(private val context: Context) {

    private val key = stringPreferencesKey("settings_json")

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        prefs[key]?.let { raw ->
            runCatching { Json.decodeFromString<AppSettings>(raw) }.getOrNull()
        } ?: AppSettings()
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs[key]?.let { raw ->
                runCatching { Json.decodeFromString<AppSettings>(raw) }.getOrNull()
            } ?: AppSettings()
            prefs[key] = Json.encodeToString(transform(current))
        }
    }

    suspend fun reset() {
        context.settingsDataStore.edit { it.remove(key) }
    }
}
