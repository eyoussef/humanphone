package dev.humanagent.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * One call the model wants to make. [arguments] is the raw JSON object string produced by the
 * model, kept unparsed so a malformed payload can be reported back to it instead of crashing.
 */
@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String = "{}",
)

/** A document attached to a turn, sent to providers that accept `file` content parts. */
@Serializable
data class FilePart(
    val fileName: String,
    val mimeType: String,
    val base64: String,
)

/** A single turn in an OpenAI-compatible conversation. */
@Serializable
data class Message(
    val role: String,
    val content: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val name: String? = null,
    /** Base64 JPEG payloads attached to this turn for vision models. */
    val images: List<String> = emptyList(),
    /** Documents attached to this turn; Ollama rejects them, so callers must not send them there. */
    val files: List<FilePart> = emptyList(),
) {
    companion object {
        fun system(text: String) = Message(role = "system", content = text)
        fun user(
            text: String,
            images: List<String> = emptyList(),
            files: List<FilePart> = emptyList(),
        ) = Message(role = "user", content = text, images = images, files = files)
        fun assistant(text: String) = Message(role = "assistant", content = text)
        fun tool(callId: String, name: String, text: String) =
            Message(role = "tool", content = text, toolCallId = callId, name = name)
    }
}

/** Tool description handed to the model in the `tools` array. */
@Serializable
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

/** Streaming events emitted by [LlmClient.stream]. */
sealed interface StreamEvent {
    /** Incremental assistant text. */
    data class TextDelta(val text: String) : StreamEvent

    /** The turn finished; [message] carries the full assistant message including tool calls. */
    data class Completed(val message: Message) : StreamEvent

    /** Transport or protocol failure. The flow terminates after this. */
    data class Failure(val message: String) : StreamEvent
}

enum class ProviderKind(
    val label: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val needsApiKey: Boolean,
) {
    OLLAMA(
        label = "Ollama (local)",
        defaultBaseUrl = "http://127.0.0.1:11434/v1",
        defaultModel = "deepseek-v4.1-flash:cloud",
        needsApiKey = false,
    ),
    OPENROUTER(
        label = "OpenRouter",
        defaultBaseUrl = "https://openrouter.ai/api/v1",
        defaultModel = "deepseek/deepseek-v4.1-flash",
        needsApiKey = true,
    ),
    CUSTOM(
        label = "Custom OpenAI-compatible",
        defaultBaseUrl = "",
        defaultModel = "",
        needsApiKey = false,
    );
}

data class ProviderConfig(
    val kind: ProviderKind,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val temperature: Double = 0.6,
    val maxTokens: Int = 2048,
) {
    /** Endpoint the client posts to, tolerating a base URL with or without the trailing version. */
    val chatCompletionsUrl: String
        get() {
            val trimmed = baseUrl.trimEnd('/')
            return if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
        }

    val isUsable: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank() && (!kind.needsApiKey || apiKey.isNotBlank())
}
