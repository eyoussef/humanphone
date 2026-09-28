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
        chatEngine = ChatEngine(this, settingsStore, speaker, memory)
        scope.launch {
            memory.load()
            chatEngine.refresh()
            // Every settings emission re-points voice input at the chosen speech-to-text backend.
            settingsStore.settings.collect { voice.updateConfig(it.toSttConfig()) }
        }
    }

    companion object {
        lateinit var instance: HumanPhoneApp
            private set
    }
}
