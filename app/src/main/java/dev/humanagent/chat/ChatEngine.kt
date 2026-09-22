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
import dev.humanagent.llm.ToolSpec
import dev.humanagent.util.ImagePrep
import dev.humanagent.util.JsonArgs
import dev.humanagent.util.Markdown
import dev.humanagent.voice.Speaker
import dev.humanagent.voice.VoiceIO
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The talking half of the assistant: streaming chat with memory, plus the bridge that hands a
 * `/do …` message over to the phone-operating agent.
 */
class ChatEngine(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val speaker: Speaker,
    private val memory: MemoryStore,
    private val voice: VoiceIO,
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
    private var liveJob: Job? = null

    private val _liveMode = MutableStateFlow(false)

    /** Hands-free conversation: after every spoken reply the microphone opens again. */
    val liveMode: StateFlow<Boolean> = _liveMode.asStateFlow()

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

    /** Sends text plus attached pictures; the last user turn carries them to the model. */
    fun sendWithImages(text: String, imagePaths: List<String>) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && imagePaths.isEmpty()) return
        if (_streaming.value) return
        append(ChatTurn(ROLE_USER, trimmed, now(), imagePaths = imagePaths))
        _error.value = null
        persist()
        job = scope.launch { respond() }
    }

    /** Sends a recorded voice note; it is kept in the transcript and marked for the model. */
    fun sendVoiceNote(path: String) {
        if (_streaming.value) return
        append(ChatTurn(ROLE_USER, "", now(), audioPath = path))
        _error.value = null
        persist()
        job = scope.launch { respond() }
    }

    /** Keeps a voice note in the transcript without asking the model anything. */
    fun attachVoiceNote(path: String) {
        append(ChatTurn(ROLE_USER, "", now(), audioPath = path))
        persist()
    }

    /** Flips live mode, remembers it and starts or stops listening. */
    fun toggleLiveMode() {
        val next = !_liveMode.value
        _liveMode.value = next
        scope.launch { settingsStore.update { it.copy(liveMode = next) } }
        if (next) {
            startLiveLoop()
        } else {
            liveJob?.cancel()
            liveJob = null
            voice.stopListening()
        }
    }

    /** Applies live mode from settings without writing them back. */
    fun setLiveMode(enabled: Boolean) {
        if (_liveMode.value == enabled) return
        _liveMode.value = enabled
        if (enabled) {
            startLiveLoop()
        } else {
            liveJob?.cancel()
            liveJob = null
            voice.stopListening()
        }
    }

    /**
     * Who has taken the microphone away from a live conversation. Pauses are counted by reason:
     * the loop only starts listening again once the last one is released.
     */
    enum class LivePause(
        /** True when releasing this reason hands new work (a reply or a phone task) to the engine. */
        val handsOverWork: Boolean,
    ) {
        /** A voice note is being recorded in the chat. */
        VOICE_NOTE(handsOverWork = true),

        /** The agent screen is dictating a command. */
        DICTATION(handsOverWork = true),

        /** The dot is dictating a command over another app. */
        BUBBLE(handsOverWork = true),

        /** The app is not on screen, so nothing should hold the microphone. */
        BACKGROUND(handsOverWork = false),
    }

    private val livePauses = mutableSetOf<LivePause>()

    /**
     * Hands the microphone over to another capture, such as a voice note or a dictation started on
     * the agent screen: the live loop stops listening until the same [reason] is released, so one
     * utterance cannot be transcribed twice. Live mode itself stays on.
     */
    fun pauseLiveListening(reason: LivePause) {
        livePauses += reason
        if (!_liveMode.value) return
        liveJob?.cancel()
        liveJob = null
        voice.stopListening()
    }

    /**
     * Releases [reason] and, when nothing else holds the microphone, listens again — after the
     * assistant has finished speaking, so the recogniser does not transcribe its own voice.
     */
    fun resumeLiveListening(reason: LivePause) {
        livePauses -= reason
        if (!_liveMode.value || liveJob != null || livePauses.isNotEmpty()) return
        // Someone else (the dot, another dictation) is on the microphone: leave it alone.
        if (voice.isListening.value) return
        scope.launch {
            // Work this reason just handed over is only registered a moment later.
            if (reason.handsOverWork) awaitWorkAppears()
            awaitQuiet()
            if (_liveMode.value && liveJob == null && livePauses.isEmpty() && !voice.isListening.value) {
                startLiveLoop()
            }
        }
    }

    private fun startLiveLoop() {
        // Someone else is on the microphone; the loop waits until they release it.
        if (livePauses.isNotEmpty()) return
        liveJob?.cancel()
        liveJob = scope.launch {
            voice.heard.collect { utterance ->
                if (!_liveMode.value) return@collect
                val heard = utterance.trim()
                if (heard.isEmpty() || _streaming.value) return@collect
                send(heard)
                job?.join()
                // Wait for the spoken reply before opening the microphone again.
                awaitQuiet()
                if (_liveMode.value && livePauses.isEmpty()) voice.startListening()
            }
        }
        voice.startListening()
    }

    /**
     * Gives a reply or a phone task that was handed over moments ago time to register, watching both
     * at once. Without it [awaitQuiet] would look at an engine that is still idle and let the
     * microphone open in front of the answer that is on its way.
     */
    private suspend fun awaitWorkAppears() = coroutineScope {
        val streaming = async { withTimeoutOrNull(START_GRACE_MS) { _streaming.first { it } } }
        val phoneTask = async { withTimeoutOrNull(START_GRACE_MS) { AgentService.loop.first { it.running } } }
        streaming.await()
        phoneTask.await()
        Unit
    }

    /**
     * Waits until the assistant is quiet before the microphone opens again: a reply that is still
     * streaming, a phone task still running, and then the voice those produce. Without it the
     * recogniser transcribes the assistant's own words and answers them.
     */
    private suspend fun awaitQuiet() {
        withTimeoutOrNull(REPLY_WAIT_MS) { _streaming.first { !it } }
        withTimeoutOrNull(REPLY_WAIT_MS) { AgentService.loop.first { !it.running } }
        if (!settingsStore.current().speakReplies) return
        // The utterance is posted moments before this point, so a short window catches it.
        withTimeoutOrNull(SPEECH_START_GRACE_MS) { speaker.speaking.first { it } }
        withTimeoutOrNull(SPEECH_END_MS) { speaker.speaking.first { !it } }
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
        var handoff: String? = null

        try {
            LlmClient(config).stream(history(settings), listOf(phoneTool)).collect { event ->
                when (event) {
                    is StreamEvent.TextDelta -> {
                        builder.append(event.text)
                        replaceLast(builder.toString())
                    }

                    is StreamEvent.Completed -> {
                        val call = event.message.toolCalls.firstOrNull { it.name == PHONE_TOOL }
                        if (call != null) {
                            handoff = JsonArgs.string(call.arguments, "task")
                                ?: JsonArgs.string(call.arguments, "request")
                            finalText = event.message.content.takeIf { it.isNotBlank() }
                        } else {
                            finalText = event.message.content.ifBlank { builder.toString() }
                        }
                    }

                    is StreamEvent.Failure -> failure = event.message
                }
            }
        } finally {
            _streaming.value = false
        }

        val task = handoff
        if (task != null && task.isNotBlank()) {
            // The model decided this needs the phone, so the chat hands it to the operator loop.
            val preamble = finalText?.takeIf { it.isNotBlank() }
                ?: "On it — taking over the phone now. Watch the dot (or the Run tab) for each step."
            replaceLast(preamble)
            persist()
            if (settings.speakReplies) {
                mainHandler.post { runCatching { speaker.say(Markdown.strip(preamble)) } }
            }
            AgentService.run(context, task)
            return
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
        val recent = _messages.value.filter { it.text.isNotBlank() || it.audioPath != null }.takeLast(HISTORY_TURNS)
        recent.forEachIndexed { index, turn ->
            val isLast = index == recent.lastIndex
            messages += when (turn.role) {
                ROLE_USER -> Message(
                    role = ROLE_USER,
                    content = turn.text.ifBlank { if (turn.audioPath != null) "[voice note]" else "" },
                    // Only the newest turn carries pixels; older pictures are already described in the text.
                    images = if (isLast) ImagePrep.encodeAll(turn.imagePaths) else emptyList(),
                )

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
        append("- When the user asks you to do something on this phone — open an app, search or read the web, ")
        append("send a message, set an alarm, change a setting — call the $PHONE_TOOL tool with the whole task ")
        append("instead of describing the steps. Answer in words only for things you can answer yourself.\n")
        append("- Keep replies short, warm and spoken-friendly: they may be read out loud.\n")
        append("- Write plain conversational text: never use markdown asterisks, hashes, tables or bullet symbols.\n")
        append("- A message marked \"[voice note]\" is a recording you cannot hear: answer in one short line, ")
        append("say you cannot play audio, and ask them to dictate it with the microphone or type it instead.\n")
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
        val title = ConversationStore.titleFor(turns.firstOrNull { it.role == ROLE_USER })
        val active = Conversation(id, title, System.currentTimeMillis(), turns)
        val others = _conversations.value.filterNot { it.id == id }
        return listOf(active) + others
    }

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        const val DO_PREFIX = "/do"
        private const val PHONE_TOOL = "operate_phone"
        private const val ROLE_USER = "user"
        private const val ROLE_ASSISTANT = "assistant"
        private const val HISTORY_TURNS = 24

        /** How long a resume waits for a reply that is about to be spoken before giving up on it. */
        private const val SPEECH_START_GRACE_MS = 1_000L

        /** Window for work handed over a moment ago to register before it is waited for. */
        private const val START_GRACE_MS = 1_000L

        /** Ceiling for a spoken reply to finish before the microphone reopens. */
        private const val SPEECH_END_MS = 30_000L

        /** Ceiling for a reply or a phone task to finish before the microphone reopens. */
        private const val REPLY_WAIT_MS = 90_000L

        /**
         * The one tool the chat half owns: when the model calls it, the words stop and the operator
         * loop takes the phone over. This is what keeps the assistant from merely explaining steps.
         */
        private val phoneTool = ToolSpec(
            name = PHONE_TOOL,
            description = "Take over this phone and do the task for real: open apps, search the web, read a page, " +
                "tap, type, scroll, send a message, set an alarm. Call it whenever the user asks for something " +
                "to be done on the phone instead of explaining how they could do it themselves.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("task") {
                        put("type", "string")
                        put("description", "The task to carry out on the phone, stated plainly and completely.")
                    }
                }
                putJsonArray("required") { add("task") }
            },
        )
    }
}
