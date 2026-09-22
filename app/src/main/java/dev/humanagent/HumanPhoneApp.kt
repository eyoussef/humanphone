package dev.humanagent

import android.app.Application
import dev.humanagent.agent.MemoryStore
import dev.humanagent.chat.ChatEngine
import dev.humanagent.llm.SettingsStore
import dev.humanagent.voice.Speaker
import dev.humanagent.voice.VoiceIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Single owner of the long-lived pieces so activities and services share one brain. */
class HumanPhoneApp : Application() {

    lateinit var settingsStore: SettingsStore
        private set
    lateinit var memory: MemoryStore
        private set
    lateinit var speaker: Speaker
        private set
    lateinit var voice: VoiceIO
        private set
    lateinit var chatEngine: ChatEngine
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        instance = this
        settingsStore = SettingsStore(this)
        memory = MemoryStore(this)
        speaker = Speaker(this)
        voice = VoiceIO(this)
        chatEngine = ChatEngine(this, settingsStore, speaker, memory, voice)
        scope.launch {
            memory.load()
            chatEngine.refresh()
        }
        scope.launch {
            // Languages and the voice's speed and pitch follow the settings from the very first
            // emission; live mode is only applied from later changes, and MainActivity restores a
            // stored session when the user actually opens the app.
            var startingUp = true
            settingsStore.settings.collect { settings ->
                voice.setLanguage(settings.sttLanguage)
                voice.setPreferOffline(settings.sttPreferOffline)
                speaker.setLanguage(settings.ttsLanguage)
                speaker.setSpeechRate(settings.ttsSpeechRate)
                speaker.setPitch(settings.ttsPitch)
                if (!startingUp) chatEngine.setLiveMode(settings.liveMode)
                startingUp = false
            }
        }
    }

    companion object {
        lateinit var instance: HumanPhoneApp
            private set
    }
}
