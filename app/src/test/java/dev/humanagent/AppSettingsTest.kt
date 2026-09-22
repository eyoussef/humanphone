package dev.humanagent

import dev.humanagent.llm.AppSettings
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {

    private val json = Json

    /** Keys added after the first release; a stored file predates them. */
    private val newerKeys = setOf("ttsSpeechRate", "ttsPitch", "sttPreferOffline")

    @Test
    fun settingsFileFromAnOlderBuildLoadsWithDefaultsForNewKeys() {
        val stored = json.encodeToString(
            AppSettings(
                model = "llama3",
                ttsLanguage = "en-GB",
                speakReplies = false,
                ttsSpeechRate = 1.6f,
                ttsPitch = 0.8f,
                sttPreferOffline = true,
            )
        )
        val legacy = json.parseToJsonElement(stored).jsonObject
            .filterKeys { it !in newerKeys }
        assertTrue(legacy.keys.none { it in newerKeys })

        val loaded = json.decodeFromString<AppSettings>(JsonObject(legacy).toString())

        assertEquals("llama3", loaded.model)
        assertEquals("en-GB", loaded.ttsLanguage)
        assertEquals(false, loaded.speakReplies)
        assertEquals(1.0f, loaded.ttsSpeechRate)
        assertEquals(1.0f, loaded.ttsPitch)
        assertEquals(false, loaded.sttPreferOffline)
    }

    @Test
    fun voiceSettingsSurviveARoundTrip() {
        val original = AppSettings(
            sttLanguage = "ar",
            ttsLanguage = "de-DE",
            ttsSpeechRate = 1.75f,
            ttsPitch = 0.5f,
            sttPreferOffline = true,
            liveMode = true,
        )

        val loaded = json.decodeFromString<AppSettings>(json.encodeToString(original))

        assertEquals(original, loaded)
    }
}
