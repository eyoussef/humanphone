package dev.humanagent.chat

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.humanagent.agent.AgentService
import dev.humanagent.agent.MemoryStore
import dev.humanagent.llm.LlmClient
import dev.humanagent.llm.Message
import dev.humanagent.llm.SettingsStore
import dev.humanagent.llm.StreamEvent
import dev.humanagent.util.Markdown
import dev.humanagent.voice.Speaker
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The talking half of the assistant: streaming chat with memory, plus the bridge that hands a
 * `/do …` message over to the phone-operating agent.
 */
class ChatEngine(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val speaker: Speaker,
    private val memory: MemoryStore,
) {

    private val store = ConversationStore(File(context.filesDir, "conversations.json"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _activeId = MutableStateFlow("")
    val activeId: StateFlow<String> = _activeId.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatTurn>>(emptyList())
    val messages: StateFlow<List<ChatTurn>> = _messages.asStateFlow()

    private val _streaming = MutableStateFlow(false)
    val streaming: StateFlow<Boolean> = _streaming.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var job: Job? = null

    suspend fun refresh() {
        val loaded = store.load().sortedByDescending { it.updatedAtMs }
        if (loaded.isEmpty()) {
            newConversation()
            return
        }
        _conversations.value = loaded
        openConversation(loaded.first().id)
    }

    fun newConversation() {
        val id = ConversationStore.newId()
        _conversations.update { current ->
            listOf(Conversation(id, "New chat", System.currentTimeMillis(), emptyList())) + current
        }
        _activeId.value = id
        _messages.value = emptyList()
        _error.value = null
        persist()
    }

    fun openConversation(id: String) {
        val conversation = _conversations.value.firstOrNull { it.id == id } ?: return
        cancel()
        _activeId.value = conversation.id
        _messages.value = conversation.turns
        _error.value = null
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _streaming.value) return

        if (trimmed.startsWith(DO_PREFIX)) {
            val command = trimmed.removePrefix(DO_PREFIX).trim()
            if (command.isEmpty()) {
                _error.value = "Type the task after $DO_PREFIX, for example \"$DO_PREFIX text Sam that I am late\"."
                return
            }
            _error.value = null
            append(ChatTurn(ROLE_USER, trimmed, now()))
            append(
                ChatTurn(
                    ROLE_ASSISTANT,
                    "On it — taking over the phone now. Watch the dot (or the Run tab) for each step.",
                    now(),
                )
            )
            persist()
            AgentService.run(context, command)
            return
        }

        append(ChatTurn(ROLE_USER, trimmed, now()))
        _error.value = null
        persist()
        job = scope.launch { respond() }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _streaming.value = false
    }

    private suspend fun respond() {
        val settings = settingsStore.current()
        val config = settings.toProviderConfig()
        if (!config.isUsable) {
            _error.value = "Configure the model in Settings first (OpenRouter key, or an Ollama address)."
            return
        }
        _streaming.value = true
        append(ChatTurn(ROLE_ASSISTANT, "", now()))
        val builder = StringBuilder()
        var finalText: String? = null
        var failure: String? = null

        try {
            LlmClient(config).stream(history(settings)).collect { event ->
                when (event) {
                    is StreamEvent.TextDelta -> {
                        builder.append(event.text)
                        replaceLast(builder.toString())
                    }

                    is StreamEvent.Completed -> {
                        finalText = event.message.content.ifBlank { builder.toString() }
                    }

                    is StreamEvent.Failure -> failure = event.message
                }
            }
        } finally {
            _streaming.value = false
        }

        val answer = finalText?.takeIf { it.isNotBlank() }
        when {
            answer != null -> {
                replaceLast(answer)
                if (settings.speakReplies) {
                    mainHandler.post { runCatching { speaker.say(Markdown.strip(answer)) } }
                }
            }

            failure != null -> {
                _error.value = failure
                replaceLast("I could not reach the model: $failure")
            }

            else -> replaceLast("The model returned nothing. Try again.")
        }
        persist()
    }

    private fun history(settings: dev.humanagent.llm.AppSettings): List<Message> {
        val messages = ArrayList<Message>()
        messages += Message.system(systemPrompt(settings))
        _messages.value
            .filter { it.text.isNotBlank() }
            .takeLast(HISTORY_TURNS)
            .forEach { turn ->
                messages += when (turn.role) {
                    ROLE_USER -> Message.user(turn.text)
                    else -> Message.assistant(turn.text)
                }
            }
        return messages
    }

    private fun systemPrompt(settings: dev.humanagent.llm.AppSettings): String = buildString {
        append(settings.persona)
        append("\n\nYou are chatting inside the HumanPhone Android app on the user's own phone.\n")
        append("- You can operate the phone: reading the screen, tapping, typing, scrolling, opening apps, sending SMS.\n")
        append("- The user triggers that by starting a message with \"$DO_PREFIX\" followed by the task; ")
        append("when they ask for an action without it, answer and remind them of the \"$DO_PREFIX\" shortcut once.\n")
        append("- Keep replies short, warm and spoken-friendly: they may be read out loud.\n")
        append("- Write plain conversational text: never use markdown asterisks, hashes, tables or bullet symbols.\n")
        val notes = memory.snapshot()
        if (notes.isNotEmpty()) {
            append("\nWhat you remember about this user:\n")
            append(notes.entries.joinToString("\n") { "- ${it.key}: ${it.value}" })
        }
    }

    private fun append(turn: ChatTurn) {
        _messages.update { it + turn }
    }

    private fun replaceLast(text: String) {
        _messages.update { current ->
            if (current.isEmpty()) current else current.dropLast(1) + current.last().copy(text = text)
        }
    }

    private fun persist() {
        val snapshot = snapshot()
        scope.launch {
            _conversations.value = store.save(snapshot)
        }
    }

    private fun snapshot(): List<Conversation> {
        val id = _activeId.value
        val turns = _messages.value
        val title = ConversationStore.titleFor(turns.firstOrNull { it.role == ROLE_USER }?.text.orEmpty())
        val active = Conversation(id, title, System.currentTimeMillis(), turns)
        val others = _conversations.value.filterNot { it.id == id }
        return listOf(active) + others
    }

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        const val DO_PREFIX = "/do"
        private const val ROLE_USER = "user"
        private const val ROLE_ASSISTANT = "assistant"
        private const val HISTORY_TURNS = 24
    }
}
