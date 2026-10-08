package dev.humanagent.chat

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.humanagent.agent.AgentService
import dev.humanagent.agent.MemoryStore
import dev.humanagent.llm.AppSettings
import dev.humanagent.llm.FilePart
import dev.humanagent.llm.LlmClient
import dev.humanagent.llm.Message
import dev.humanagent.llm.ProviderKind
import dev.humanagent.llm.SettingsStore
import dev.humanagent.llm.StreamEvent
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
    private val brain: dev.humanagent.brain.Brain,
) {

    /** Entry point for the chat screen: files are copied into app storage when they are imported. */
    val attachments = AttachmentStore(context)

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

    /** Deletes a conversation; when it is the active one the chat moves to the newest other one. */
    fun deleteConversation(id: String) {
        val wasActive = _activeId.value == id
        if (wasActive) {
            cancel()
            _activeId.value = ""
            _messages.value = emptyList()
            _error.value = null
        }
        _conversations.value = _conversations.value.filterNot { it.id == id }
        if (wasActive) {
            val next = _conversations.value.firstOrNull()
            if (next != null) openConversation(next.id) else newConversation()
        }
        persist()
    }

    fun send(text: String, attachments: List<Attachment> = emptyList()) {
        val trimmed = text.trim()
        if (_streaming.value) return
        if (trimmed.isEmpty() && attachments.isEmpty()) return

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
                    "On it — taking over the phone now. Watch the Agent tab (or the notification) for each step.",
                    now(),
                )
            )
            persist()
            AgentService.run(context, command)
            return
        }

        append(ChatTurn(ROLE_USER, trimmed, now(), attachments))
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
                if (settings.speakReplies) mainHandler.post { runCatching { speaker.say(answer) } }
            }

            failure != null -> {
                _error.value = failure
                replaceLast("I could not reach the model: $failure")
            }

            else -> replaceLast("The model returned nothing. Try again.")
        }
        persist()
        if (answer != null) {
            val userText = _messages.value.lastOrNull { it.role == ROLE_USER }?.text.orEmpty()
            val title = _conversations.value.firstOrNull { it.id == _activeId.value }?.title.orEmpty()
            brain.rememberExchange(_activeId.value, title, userText, answer, now())
        }
    }

    /** Puts an app-produced file (a rendered document) into the active chat. */
    fun attachResult(file: java.io.File, mimeType: String, note: String) {
        scope.launch {
            val adopted = attachments.adopt(file, mimeType).getOrNull() ?: return@launch
            append(ChatTurn(ROLE_ASSISTANT, note, now(), listOf(adopted)))
            persist()
        }
    }

    private suspend fun history(settings: AppSettings): List<Message> {
        val messages = ArrayList<Message>()
        val turns = _messages.value
            .filter { it.text.isNotBlank() || it.attachments.isNotEmpty() }
            .takeLast(HISTORY_TURNS)
        // The brain adds what matters from older chats and tasks; without it the prompt is
        // exactly what it always was. Recalled photos ride with the question so the model sees them.
        val recalled = brain.recall(turns.lastOrNull { it.role == ROLE_USER }?.text.orEmpty())
        messages += Message.system(
            if (recalled != null) systemPrompt(settings) + "\n\n" + recalled.text else systemPrompt(settings),
        )
        // Only the newest image turns keep their payloads: images are heavy and the model mostly
        // needs the one the user just sent. Older ones stay visible as a line of text.
        val payloadTurns = turns.indices
            .filter { index ->
                turns[index].role == ROLE_USER &&
                    turns[index].attachments.any { it.kind == AttachmentStore.KIND_IMAGE }
            }
            .takeLast(MAX_IMAGE_TURNS)
            .toSet()
        val sendsFiles = settings.providerKind != ProviderKind.OLLAMA

        turns.forEachIndexed { index, turn ->
            if (turn.role != ROLE_USER) {
                messages += Message.assistant(turn.text)
                return@forEachIndexed
            }

            val images = ArrayList<String>()
            val files = ArrayList<FilePart>()
            val extra = StringBuilder()
            turn.attachments.forEach { attachment ->
                when (attachment.kind) {
                    AttachmentStore.KIND_IMAGE -> {
                        val payload = if (index in payloadTurns && images.size < AttachmentStore.MAX_IMAGES_PER_MESSAGE) {
                            attachments.imageBase64(attachment)
                        } else {
                            null
                        }
                        if (payload != null) {
                            images += payload
                        } else {
                            extra.appendLine("[image sent earlier: ${attachment.name}]")
                        }
                    }

                    AttachmentStore.KIND_TEXT -> {
                        val body = attachments.text(attachment, AttachmentStore.TEXT_INLINE_LIMIT + 1)
                        if (body == null) {
                            extra.appendLine("[attached file: ${attachment.name} — could not be read]")
                        } else {
                            val cut = body.length > AttachmentStore.TEXT_INLINE_LIMIT
                            extra.appendLine("--- attached file: ${attachment.name} ---")
                            extra.append(body.take(AttachmentStore.TEXT_INLINE_LIMIT))
                            if (cut) extra.append("\n…(truncated)")
                            extra.appendLine()
                            extra.appendLine("--- end of ${attachment.name} ---")
                        }
                    }

                    else -> {
                        val payload = if (sendsFiles) attachments.base64(attachment) else null
                        if (payload != null) {
                            files += FilePart(
                                fileName = attachment.name,
                                mimeType = attachment.mimeType,
                                base64 = payload,
                            )
                        } else if (sendsFiles) {
                            extra.appendLine("[attached file: ${attachment.name} — could not be read]")
                        } else {
                            extra.appendLine("[attached file: ${attachment.name} — this provider cannot read it]")
                        }
                    }
                }
            }

            val content = when {
                turn.text.isNotBlank() -> turn.text
                turn.attachments.isNotEmpty() -> attachmentSentence(turn.attachments)
                else -> ""
            }
            val builder = StringBuilder(content)
            if (extra.isNotEmpty()) {
                if (builder.isNotEmpty()) builder.append('\n')
                builder.append(extra.toString().trimEnd())
            }
            messages += Message.user(builder.toString(), images, files)
        }
        // Recalled photos ride with the question being asked, where vision models expect images.
        if (recalled != null && recalled.images.isNotEmpty()) {
            val lastIndex = messages.indexOfLast { it.role == "user" }
            if (lastIndex >= 0) {
                val last = messages[lastIndex]
                messages[lastIndex] = last.copy(images = last.images + recalled.images)
            }
        }
        return messages
    }

    /** What the model gets when the user sent files without typing anything. */
    private fun attachmentSentence(list: List<Attachment>): String {
        val noun = when {
            list.size > 1 -> "files"
            list.first().kind == AttachmentStore.KIND_IMAGE -> "image"
            else -> "file"
        }
        return "Please look at the attached $noun: ${attachments.summary(list)}."
    }

    private fun systemPrompt(settings: AppSettings): String = buildString {
        append(settings.persona)
        append("\n\nYou are chatting inside the HumanPhone Android app on the user's own phone.\n")
        append("- You can operate the phone: reading the screen, tapping, typing, scrolling, opening apps, sending SMS, comparing prices across apps and building real websites.\n")
        append("- The user triggers that by starting a message with \"$DO_PREFIX\" followed by the task; ")
        append("when they ask for an action without it, answer and remind them of the \"$DO_PREFIX\" shortcut once.\n")
        append("- The user can attach photos and documents to a message; they arrive with it, so look at them before answering.\n")
        append("- Keep replies short, warm and spoken-friendly: they may be read out loud.\n")
        val twin = memory.render()
        if (twin.isNotEmpty()) {
            append('\n')
            append(twin)
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
        val firstUser = turns.firstOrNull { it.role == ROLE_USER }
        val titleSource = firstUser?.text?.takeIf { it.isNotBlank() }
            ?: firstUser?.attachments?.firstOrNull()?.name.orEmpty()
        val title = ConversationStore.titleFor(titleSource)
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

        /** Newest user turns whose image payloads still travel in full. */
        private const val MAX_IMAGE_TURNS = 2
    }
}
