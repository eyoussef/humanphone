package dev.humanagent.agent

import android.content.Context
import android.util.Log
import dev.humanagent.llm.AppSettings
import dev.humanagent.llm.LlmClient
import dev.humanagent.llm.Message
import dev.humanagent.llm.SettingsStore
import dev.humanagent.llm.StreamEvent
import dev.humanagent.util.Markdown
import dev.humanagent.voice.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One thing that happened while the assistant was working, shown live in the UI. */
data class AgentStep(
    val index: Int,
    val kind: String,
    val title: String,
    val detail: String,
    val timestampMs: Long,
)

data class AgentRunState(
    val running: Boolean = false,
    val transcript: List<AgentStep> = emptyList(),
    val lastReply: String = "",
    val error: String? = null,
    val liveText: String = "",
)

/**
 * The mind: it looks at the screen, decides, acts, and looks again, until the task is done.
 * Everything it does is driven by the model through the tool definitions in [AgentTools].
 */
class AgentLoop(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val memory: MemoryStore,
    private val speaker: Speaker,
) {

    private val _state = MutableStateFlow(AgentRunState())
    val state: StateFlow<AgentRunState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun run(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return
        halt()
        job = scope.launch { execute(trimmed) }
    }

    fun halt() {
        job?.cancel()
        job = null
        _state.update { it.copy(running = false, liveText = "") }
    }

    fun shutdown() {
        halt()
    }

    private suspend fun execute(command: String) {
        _state.value = AgentRunState(running = true)
        step("task", "Task", command)

        val settings = settingsStore.current()
        val config = settings.toProviderConfig()
        if (!config.isUsable) {
            fail("Configure the model in Settings first (OpenRouter key or Ollama address).")
            return
        }
        val service = AgentAccessibilityService.instance
        if (service == null) {
            fail("Turn on the HumanPhone accessibility service in Settings → Permissions.")
            return
        }

        val reader = ScreenReader(service)
        val executor = UiActionExecutor(service, reader)
        val tools = AgentTools(context, memory, speaker)
        val client = LlmClient(config)
        val conversation = mutableListOf<Message>()
        conversation += Message.system(buildSystemPrompt(settings))
        conversation += Message.user(command)

        var stepIndex = 0
        var answered = false
        while (stepIndex < settings.maxSteps && currentCoroutineContext().isActive) {
            stepIndex++
            val snapshot = reader.snapshot()
            val screenshot = if (settings.sendScreenshots) {
                runCatching { service.captureScreenshotBase64() }.getOrNull()
            } else {
                null
            }
            conversation += Message(
                role = "user",
                content = "Step $stepIndex. Current screen:\n${snapshot.rendered}",
                images = listOfNotNull(screenshot),
            )

            val assistant = askModel(client, conversation, tools)
            if (assistant == null) {
                if (_state.value.error == null) fail("The model stopped responding.")
                return
            }
            conversation += assistant

            if (assistant.toolCalls.isEmpty()) {
                val reply = assistant.content.ifBlank { "I stopped without a result." }
                step("reply", "Assistant", reply)
                if (settings.speakReplies) say(reply)
                _state.update { it.copy(lastReply = reply, liveText = "") }
                answered = true
                break
            }

            var terminal = false
            for (call in assistant.toolCalls) {
                if (!currentCoroutineContext().isActive) return
                step("action", call.name, call.arguments)
                val outcome = tools.execute(call, executor, service, settings)
                val payload = if (outcome.includeScreen) {
                    outcome.text + "\n\n" + reader.snapshot().rendered
                } else {
                    outcome.text
                }
                conversation += Message.tool(call.id, call.name, payload.take(8_000))
                step("result", call.name, outcome.text)
                if (outcome.terminal) {
                    terminal = true
                    val summary = outcome.text
                    _state.update { it.copy(lastReply = summary, liveText = "") }
                    if (settings.speakReplies) say(summary)
                    answered = true
                }
            }

            trim(conversation)
            if (terminal) break
            delay(settings.stepDelayMs.coerceAtLeast(0).toLong())
        }

        if (!answered && _state.value.error == null) {
            val note = "I used my ${settings.maxSteps} steps without finishing. Tell me to continue if you want me to keep going."
            step("reply", "Assistant", note)
            _state.update { it.copy(lastReply = note, liveText = "") }
            if (settings.speakReplies) say(note)
        }
        _state.update { it.copy(running = false, liveText = "") }
    }

    private suspend fun askModel(
        client: LlmClient,
        conversation: List<Message>,
        tools: AgentTools,
    ): Message? = withContext(Dispatchers.IO) {
        var completed: Message? = null
        val live = StringBuilder()
        client.stream(conversation, tools.specs).collect { event ->
            when (event) {
                is StreamEvent.TextDelta -> {
                    live.append(event.text)
                    _state.update { it.copy(liveText = live.toString().takeLast(400)) }
                }

                is StreamEvent.Completed -> completed = event.message
                is StreamEvent.Failure -> fail(event.message)
            }
        }
        completed
    }

    private fun buildSystemPrompt(settings: AppSettings): String = buildString {
        append(settings.persona)
        append("\n\nYou are now driving this Android phone for real, through the accessibility service.\n")
        append("How you work:\n")
        append("- The latest screen dump is given to you at every step, with an index for each element.\n")
        append("- Use those indices or the visible wording to act; never invent elements.\n")
        append("- Take one or two actions, then look at the screen again before the next move.\n")
        append("- Use find_contact before call or send_sms when you only know a name.\n")
        append("- Use speak when the user should hear progress, and finish the moment the goal is met, blocked, or needs the user.\n")
        append("- If the user writes in another language, answer in that language.\n")
        append("- Speak and write plain sentences: no markdown asterisks, hashes or bullet symbols.\n")
        if (settings.sendScreenshots) {
            append("- You also receive a screenshot of the screen every step; use it for images, games and canvas content.\n")
        }
        val notes = memory.snapshot()
        append("\nWhat you remember about this user:\n")
        append(if (notes.isEmpty()) "Nothing yet." else notes.entries.joinToString("\n") { "- ${it.key}: ${it.value}" })
    }

    private fun trim(conversation: MutableList<Message>) {
        val keepTail = 24
        if (conversation.size <= keepTail + 1) return
        val system = conversation.first()
        val dropped = conversation.size - keepTail - 1
        repeat(dropped) { conversation.removeAt(1) }
        if (conversation.first() !== system) conversation.add(0, system)
    }

    private fun step(kind: String, title: String, detail: String) {
        _state.update { current ->
            val next = current.transcript + AgentStep(
                index = current.transcript.size + 1,
                kind = kind,
                title = title,
                detail = detail.take(1_200),
                timestampMs = System.currentTimeMillis(),
            )
            current.copy(transcript = next.takeLast(200))
        }
        Log.i(TAG, "$kind · $title · ${detail.take(200)}")
    }

    private fun fail(message: String) {
        step("error", "Problem", message)
        _state.update { it.copy(error = message, running = false, liveText = "") }
        say(message)
    }

    /** Speaker calls belong on the main thread; the loop runs on a background dispatcher. */
    private fun say(text: String) {
        mainHandler.post { runCatching { speaker.say(Markdown.strip(text)) } }
    }

    companion object {
        private const val TAG = "HumanPhoneAgent"
        private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    }
}
