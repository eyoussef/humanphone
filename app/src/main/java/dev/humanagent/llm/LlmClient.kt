package dev.humanagent.llm

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * Minimal OpenAI-compatible chat client with SSE streaming and tool-call accumulation.
 * Works against Ollama (`/v1`), OpenRouter and any other OpenAI-compatible endpoint.
 */
class LlmClient(
    private val config: ProviderConfig,
    private val http: OkHttpClient = sharedClient,
) {

    fun stream(
        messages: List<Message>,
        tools: List<ToolSpec> = emptyList(),
    ): Flow<StreamEvent> = callbackFlow {
        val body = buildRequestBody(messages, tools).toString()
        val builder = Request.Builder()
            .url(config.chatCompletionsUrl)
            .post(body.toRequestBody(JSON_MEDIA))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
        if (config.apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer ${config.apiKey}")
        }
        if (config.kind == ProviderKind.OPENROUTER) {
            builder.header("HTTP-Referer", "https://github.com/humanphone")
            builder.header("X-Title", "HumanPhone")
        }

        val call = http.newCall(builder.build())
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                trySend(StreamEvent.Failure("${e.javaClass.simpleName}: ${e.message ?: "request failed"}"))
                close()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    val payload = res.body
                    if (payload == null) {
                        trySend(StreamEvent.Failure("HTTP ${res.code}: empty body"))
                        close()
                        return
                    }
                    if (!res.isSuccessful) {
                        val snippet = runCatching { payload.string().take(600) }.getOrDefault("")
                        trySend(StreamEvent.Failure("HTTP ${res.code}: $snippet"))
                        close()
                        return
                    }

                    val contentType = res.header("Content-Type").orEmpty()
                    try {
                        if (contentType.contains("application/json")) {
                            // Server ignored stream=true and answered with a single JSON object.
                            val text = payload.string()
                            parseFullResponse(text)?.let { trySend(it) }
                                ?: trySend(StreamEvent.Failure("Unparseable response: ${text.take(400)}"))
                        } else {
                            val source = payload.source()
                            val accumulator = StreamAccumulator()
                            while (true) {
                                val line = source.readUtf8Line() ?: break
                                if (line.isBlank()) continue
                                if (!line.startsWith("data:")) continue
                                val data = line.removePrefix("data:").trim()
                                if (data == "[DONE]") break
                                val chunk = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull()
                                    ?: continue
                                val deltaText = accumulator.consume(chunk)
                                if (deltaText.isNotEmpty()) {
                                    trySend(StreamEvent.TextDelta(deltaText))
                                }
                            }
                            trySend(StreamEvent.Completed(accumulator.toMessage()))
                        }
                    } catch (e: Exception) {
                        trySend(StreamEvent.Failure("${e.javaClass.simpleName}: ${e.message ?: "stream error"}"))
                    } finally {
                        close()
                    }
                }
            }
        })

        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    private fun buildRequestBody(messages: List<Message>, tools: List<ToolSpec>): JsonObject =
        buildJsonObject {
            put("model", config.model)
            put("stream", true)
            put("temperature", config.temperature)
            if (config.maxTokens > 0) put("max_tokens", config.maxTokens)
            putJsonArray("messages") {
                messages.forEach { message ->
                    add(
                        buildJsonObject {
                            put("role", message.role)
                            if (message.images.isEmpty()) {
                                // Some servers reject an empty string content alongside tool calls.
                                put("content", message.content)
                            } else {
                                putJsonArray("content") {
                                    add(
                                        buildJsonObject {
                                            put("type", "text")
                                            put("text", message.content)
                                        }
                                    )
                                    message.images.forEach { data ->
                                        add(
                                            buildJsonObject {
                                                put("type", "image_url")
                                                putJsonObject("image_url") {
                                                    put("url", "data:image/jpeg;base64,$data")
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                            message.toolCallId?.let { put("tool_call_id", it) }
                            message.name?.let { put("name", it) }
                            if (message.toolCalls.isNotEmpty()) {
                                putJsonArray("tool_calls") {
                                    message.toolCalls.forEach { call ->
                                        add(
                                            buildJsonObject {
                                                put("id", call.id)
                                                put("type", "function")
                                                putJsonObject("function") {
                                                    put("name", call.name)
                                                    put("arguments", call.arguments)
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    )
                }
            }
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    tools.forEach { spec ->
                        add(
                            buildJsonObject {
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", spec.name)
                                    put("description", spec.description)
                                    put("parameters", spec.parameters)
                                }
                            }
                        )
                    }
                }
                put("tool_choice", "auto")
            }
        }

    private fun parseFullResponse(text: String): StreamEvent? {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val message = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            ?: return null
        return StreamEvent.Completed(
            Message(
                role = "assistant",
                content = message["content"]?.jsonPrimitive?.contentOrEmpty(),
                toolCalls = parseToolCalls(message["tool_calls"]),
            )
        )
    }

    private class StreamAccumulator {
        private val content = StringBuilder()
        private val calls = LinkedHashMap<Int, PartialCall>()

        private class PartialCall(val id: String) {
            var name: String = ""
            val arguments = StringBuilder()
        }

        fun consume(chunk: JsonObject): String {
            val choice = chunk["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return ""
            val delta = choice["delta"]?.jsonObject ?: return ""
            delta["tool_calls"]?.let { raw ->
                if (raw is JsonArray) {
                    raw.forEachIndexed { position, element ->
                        val call = element.jsonObject
                        val index = call["index"]?.jsonPrimitive?.contentOrEmpty()?.toIntOrNull() ?: position
                        val partial = calls.getOrPut(index) {
                            PartialCall(call["id"]?.jsonPrimitive?.contentOrEmpty().ifEmpty { "call_$index" })
                        }
                        val function = call["function"]?.jsonObject
                        function?.get("name")?.jsonPrimitive?.contentOrEmpty()?.let { if (it.isNotEmpty()) partial.name = it }
                        function?.get("arguments")?.jsonPrimitive?.contentOrEmpty()?.let { partial.arguments.append(it) }
                    }
                }
            }
            return delta["content"]?.jsonPrimitive?.contentOrEmpty().also { content.append(it) }
        }

        fun toMessage(): Message = Message(
            role = "assistant",
            content = content.toString(),
            toolCalls = calls.values
                .filter { it.name.isNotBlank() }
                .mapIndexed { index, partial ->
                    ToolCall(
                        id = partial.id.ifBlank { "call_$index" },
                        name = partial.name,
                        arguments = partial.arguments.toString().ifBlank { "{}" },
                    )
                },
        )
    }

    private fun parseToolCalls(element: kotlinx.serialization.json.JsonElement?): List<ToolCall> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapIndexedNotNull { index, raw ->
            val call = raw as? JsonObject ?: return@mapIndexedNotNull null
            val function = call["function"]?.jsonObject ?: return@mapIndexedNotNull null
            val name = function["name"]?.jsonPrimitive?.contentOrEmpty()
            if (name.isBlank()) return@mapIndexedNotNull null
            ToolCall(
                id = call["id"]?.jsonPrimitive?.contentOrEmpty().ifBlank { "call_$index" },
                name = name,
                arguments = function["arguments"]?.jsonPrimitive?.contentOrEmpty().ifBlank { "{}" },
            )
        }
    }

    private fun kotlinx.serialization.json.JsonPrimitive?.contentOrEmpty(): String =
        if (this == null || this is kotlinx.serialization.json.JsonNull) "" else content

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
            encodeDefaults = false
        }

        val sharedClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
