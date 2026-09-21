package dev.humanagent.agent

import android.content.Context
import dev.humanagent.llm.ToolCall
import dev.humanagent.llm.ToolSpec
import dev.humanagent.util.JsonArgs
import dev.humanagent.voice.Speaker
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** What a tool produced. [includeScreen] appends a fresh screen dump so the model stays grounded. */
data class ToolOutcome(
    val text: String,
    val terminal: Boolean = false,
    val includeScreen: Boolean = true,
)

/**
 * The hand-shaped surface the model calls: the same moves a person makes, plus a few phone-native
 * shortcuts (alarm, SMS, contacts) that a person would also use.
 */
class AgentTools(
    private val context: Context,
    private val memory: MemoryStore,
    private val speaker: Speaker,
) {

    val specs: List<ToolSpec> = buildList {
        add(
            ToolSpec(
                name = "read_screen",
                description = "Read the screen again. Use after the app changed on its own or before deciding the next move.",
                parameters = schema(),
            )
        )
        add(
            ToolSpec(
                name = "tap",
                description = "Tap the element with this index from the latest screen dump.",
                parameters = schema(listOf("index"), "index" to intProp("element index")),
            )
        )
        add(
            ToolSpec(
                name = "tap_text",
                description = "Tap the first element whose visible text or description contains this string. Preferred over tap when the wording is stable.",
                parameters = schema(listOf("text"), "text" to stringProp("text to look for")),
            )
        )
        add(
            ToolSpec(
                name = "type_text",
                description = "Type into a text field. Pass index when the dump shows more than one field; omit it to use the focused field.",
                parameters = schema(
                    listOf("text"),
                    "text" to stringProp("characters to type"),
                    "index" to intProp("optional field index"),
                ),
            )
        )
        add(
            ToolSpec(
                name = "clear_text",
                description = "Empty the text field at this index.",
                parameters = schema(listOf("index"), "index" to intProp("field index")),
            )
        )
        add(
            ToolSpec(
                name = "scroll",
                description = "Swipe inside the current screen. up/down/left/right describe where the finger moves.",
                parameters = schema(
                    listOf("direction"),
                    "direction" to stringProp("finger direction", listOf("up", "down", "left", "right")),
                ),
            )
        )
        add(
            ToolSpec(
                name = "navigate",
                description = "System navigation: back, home, recents, notifications, quick_settings, lock, power_dialog.",
                parameters = schema(
                    listOf("action"),
                    "action" to stringProp(
                        "system action",
                        listOf("back", "home", "recents", "notifications", "quick_settings", "lock", "power_dialog"),
                    ),
                ),
            )
        )
        add(
            ToolSpec(
                name = "open_app",
                description = "Open an installed app by name or package, e.g. \"Telegram\" or \"com.whatsapp\".",
                parameters = schema(listOf("name"), "name" to stringProp("app name")),
            )
        )
        add(
            ToolSpec(
                name = "list_apps",
                description = "List the launchable apps installed on this phone.",
                parameters = schema(),
            )
        )
        add(
            ToolSpec(
                name = "open_url",
                description = "Open a link in the browser.",
                parameters = schema(listOf("url"), "url" to stringProp("web address")),
            )
        )
        add(
            ToolSpec(
                name = "call",
                description = "Open the dialler with a number ready; the user presses call.",
                parameters = schema(listOf("number"), "number" to stringProp("phone number")),
            )
        )
        add(
            ToolSpec(
                name = "send_sms",
                description = "Send a text message, or pre-fill it when the SMS permission is missing.",
                parameters = schema(
                    listOf("number", "message"),
                    "number" to stringProp("phone number"),
                    "message" to stringProp("message body"),
                ),
            )
        )
        add(
            ToolSpec(
                name = "set_alarm",
                description = "Create an alarm in the clock app.",
                parameters = schema(
                    listOf("hour", "minute"),
                    "hour" to intProp("hour 0-23"),
                    "minute" to intProp("minute 0-59"),
                    "label" to stringProp("optional label"),
                ),
            )
        )
        add(
            ToolSpec(
                name = "system_settings",
                description = "Open a system settings screen: wifi, bluetooth, display, sound, battery, apps, location.",
                parameters = schema(listOf("section"), "section" to stringProp("settings section")),
            )
        )
        add(
            ToolSpec(
                name = "find_contact",
                description = "Look up phone numbers for a contact name.",
                parameters = schema(listOf("name"), "name" to stringProp("contact name")),
            )
        )
        add(
            ToolSpec(
                name = "remember",
                description = "Store a durable note about the user for future sessions.",
                parameters = schema(
                    listOf("key", "value"),
                    "key" to stringProp("short label"),
                    "value" to stringProp("what to remember"),
                ),
            )
        )
        add(
            ToolSpec(
                name = "recall",
                description = "Read back everything you remember about the user.",
                parameters = schema(),
            )
        )
        add(
            ToolSpec(
                name = "speak",
                description = "Say something out loud to the user right now, for example while working on a long task.",
                parameters = schema(listOf("text"), "text" to stringProp("sentence to speak")),
            )
        )
        add(
            ToolSpec(
                name = "wait",
                description = "Pause for a moment so an app can load, between 1 and 30 seconds.",
                parameters = schema(listOf("seconds"), "seconds" to intProp("seconds to wait")),
            )
        )
        add(
            ToolSpec(
                name = "finish",
                description = "End the task. Use it as soon as the goal is reached, blocked, or needs the user.",
                parameters = schema(listOf("summary"), "summary" to stringProp("what happened, in one or two sentences")),
            )
        )
    }

    suspend fun execute(
        call: ToolCall,
        executor: UiActionExecutor,
        service: AgentAccessibilityService,
        settings: dev.humanagent.llm.AppSettings,
    ): ToolOutcome {
        val args = call.arguments
        return try {
            when (call.name) {
                "read_screen" -> ToolOutcome("Screen read.", includeScreen = true)
                "tap" -> ToolOutcome(executor.tapIndex(requireInt(args, "index")))
                "tap_text" -> ToolOutcome(executor.tapText(requireString(args, "text")))
                "type_text" -> ToolOutcome(
                    executor.setText(JsonArgs.int(args, "index"), requireString(args, "text"))
                )
                "clear_text" -> ToolOutcome(executor.clearText(requireInt(args, "index")))
                "scroll" -> ToolOutcome(executor.scroll(requireString(args, "direction")))
                "navigate" -> ToolOutcome(executor.globalAction(requireString(args, "action")), includeScreen = false)
                "open_app" -> ToolOutcome(executor.openApp(requireString(args, "name")))
                "list_apps" -> ToolOutcome(executor.listApps(), includeScreen = false)
                "open_url" -> ToolOutcome(executor.openUrl(requireString(args, "url")), includeScreen = false)
                "call" -> ToolOutcome(executor.dial(requireString(args, "number")), includeScreen = false)
                "send_sms" -> ToolOutcome(
                    executor.sendSms(requireString(args, "number"), requireString(args, "message")),
                    includeScreen = false,
                )
                "set_alarm" -> ToolOutcome(
                    executor.setAlarm(
                        requireInt(args, "hour"),
                        requireInt(args, "minute"),
                        JsonArgs.string(args, "label").orEmpty(),
                    ),
                    includeScreen = false,
                )
                "system_settings" -> ToolOutcome(
                    executor.openSystemSettings(JsonArgs.string(args, "section").orEmpty()),
                    includeScreen = false,
                )
                "find_contact" -> ToolOutcome(executor.findContacts(requireString(args, "name")), includeScreen = false)
                "remember" -> {
                    val key = requireString(args, "key")
                    val value = requireString(args, "value")
                    memory.put(key, value)
                    ToolOutcome("Remembered: $key.", includeScreen = false)
                }
                "recall" -> {
                    val notes = memory.snapshot()
                    ToolOutcome(
                        if (notes.isEmpty()) {
                            "Nothing remembered yet."
                        } else {
                            notes.entries.joinToString("\n") { "- ${it.key}: ${it.value}" }
                        },
                        includeScreen = false,
                    )
                }
                "speak" -> {
                    val text = requireString(args, "text")
                    if (settings.speakReplies) speaker.say(text)
                    ToolOutcome("Said it out loud.", includeScreen = false)
                }
                "wait" -> {
                    val seconds = requireInt(args, "seconds").coerceIn(1, 30)
                    delay(seconds * 1000L)
                    ToolOutcome("Waited $seconds seconds.")
                }
                "finish" -> ToolOutcome(requireString(args, "summary"), terminal = true, includeScreen = false)
                else -> ToolOutcome(
                    "There is no tool called \"${call.name}\". Available tools: " +
                        specs.joinToString(", ") { it.name },
                    includeScreen = false,
                )
            }
        } catch (e: IllegalArgumentException) {
            ToolOutcome("Bad arguments for ${call.name}: ${e.message}", includeScreen = false)
        }
    }

    private fun requireString(args: String, key: String): String =
        JsonArgs.string(args, key) ?: throw IllegalArgumentException("\"$key\" is required")

    private fun requireInt(args: String, key: String): Int =
        JsonArgs.int(args, key) ?: throw IllegalArgumentException("\"$key\" must be a whole number")

    private fun schema(required: List<String> = emptyList(), vararg properties: Pair<String, JsonObject>): JsonObject =
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                properties.forEach { (name, spec) -> put(name, spec) }
            }
            putJsonArray("required") {
                required.forEach { add(it) }
            }
        }

    private fun stringProp(description: String, enum: List<String>? = null): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
        if (enum != null) {
            putJsonArray("enum") { enum.forEach { add(it) } }
        }
    }

    private fun intProp(description: String): JsonObject = buildJsonObject {
        put("type", "integer")
        put("description", description)
    }
}
