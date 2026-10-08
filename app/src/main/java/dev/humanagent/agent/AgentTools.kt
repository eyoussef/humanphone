package dev.humanagent.agent

import android.content.Context
import dev.humanagent.llm.LlmClient
import dev.humanagent.llm.ToolCall
import dev.humanagent.llm.ToolSpec
import dev.humanagent.site.SiteServer
import dev.humanagent.site.SiteWorkspace
import dev.humanagent.util.JsonArgs
import dev.humanagent.voice.Speaker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

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
    private val ledger: RunLedger,
    private val speaker: Speaker,
    private val skills: SkillStore,
    /** The owed-result obligation this run works off, or null for a task that owes nothing. */
    private val obligation: Obligation? = null,
    private val brain: dev.humanagent.brain.Brain,
) {

    private val board = OfferBoard()
    private val workspace: SiteWorkspace by lazy { SiteWorkspace(context) }
    private val siteServer: SiteServer by lazy { SiteServer { workspace.currentDir() } }
    private val docs: dev.humanagent.doc.DocWorkspace by lazy { dev.humanagent.doc.DocWorkspace(context) }
    private val confirmations = mutableMapOf<String, Int>()
    private val refusedFinishes = mutableMapOf<String, Int>()

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
                description = "Tap an element whose visible text, description or hint contains this string. Exact matches come first; pass occurrence to take a later match. Preferred over tap when the wording is stable.",
                parameters = schema(
                    listOf("text"),
                    "text" to stringProp("text to look for"),
                    "occurrence" to intProp("optional 1-based match number when several elements contain the text"),
                ),
            )
        )
        add(
            ToolSpec(
                name = "find_text",
                description = "List the elements whose text, description or hint contains this string, with their dump indices. Use before tapping when the screen is crowded or the wording repeats.",
                parameters = schema(listOf("query"), "query" to stringProp("text to look for")),
            )
        )
        add(
            ToolSpec(
                name = "long_press",
                description = "Press and hold an element by index or by text, for context menus, reply popups and multi-select.",
                parameters = schema(
                    emptyList(),
                    "index" to intProp("optional element index"),
                    "query" to stringProp("optional text to look for"),
                ),
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
                name = "press_enter",
                description = "Submit the focused text field with the keyboard's enter action, which is how prompts, chat messages and searches are sent. Falls back to tapping a send, generate, submit or search button.",
                parameters = schema(),
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
                name = "scroll_to_text",
                description = "Scroll until this text appears, looking at the screen again after every swipe. Use it for menus and toolbars that scroll sideways and for lists whose item is off screen.",
                parameters = schema(
                    listOf("query"),
                    "query" to stringProp("text you are looking for"),
                    "direction" to stringProp("finger direction", listOf("up", "down", "left", "right")),
                    "max_swipes" to intProp("optional swipe limit, default 6"),
                ),
            )
        )
        add(
            ToolSpec(
                name = "scroll_in",
                description = "Scroll one specific container by its dump index, for example the sideways-scrolling bottom toolbar of an app. The element must be marked scrollable in the dump.",
                parameters = schema(
                    listOf("index", "direction"),
                    "index" to intProp("index of the scrollable container"),
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
                name = "forget",
                description = "Delete one remembered fact by its exact key, for example when the user corrects it. Use clear_memory to wipe all facts at once.",
                parameters = schema(listOf("key"), "key" to stringProp("exact key of the fact to delete")),
            )
        )
        add(
            ToolSpec(
                name = "clear_memory",
                description = "Delete every remembered fact about the user. Only when the user asks for it.",
                parameters = schema(),
            )
        )
        add(
            ToolSpec(
                name = "app_skill",
                description = "Read the built-in operating notes and any learned steps for an app. Omit app to get the notes for the app currently on screen.",
                parameters = schema(
                    emptyList(),
                    "app" to stringProp("optional app name or package, e.g. Canva or com.canva.editor"),
                ),
            )
        )
        add(
            ToolSpec(
                name = "save_skill",
                description = "Store a step-by-step procedure that worked, so the same app is easier next time. Steps are short imperative sentences in the order they were performed.",
                parameters = schema(
                    listOf("app", "steps"),
                    "app" to stringProp("app name, e.g. Canva"),
                    "package" to stringProp("optional app package, e.g. com.canva.editor"),
                    "steps" to stringArrayProp("the steps that worked, in order"),
                ),
            )
        )
        add(
            ToolSpec(
                name = "remember_person",
                description = "Remember who someone is to the user: how they relate (brother, manager...), where they talk (WhatsApp, SMS...), and how to treat them. Use it when a conversation reveals something durable about a person.",
                parameters = schema(
                    listOf("name"),
                    "name" to stringProp("the person's name, e.g. Sam"),
                    "relation" to stringProp("optional how they relate to the user, e.g. brother or manager"),
                    "channel" to stringProp("optional where they talk, e.g. WhatsApp or SMS +212..."),
                    "note" to stringProp("optional one durable line about them, e.g. prefers Arabic"),
                ),
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
                name = "wait_for_text",
                description = "Wait until this text appears on screen, up to 60 seconds, then report where it landed. Use after anything that generates, uploads or loads a result instead of tapping blindly.",
                parameters = schema(
                    listOf("query"),
                    "query" to stringProp("text you are waiting for"),
                    "timeout_seconds" to intProp("optional seconds to wait, default 20"),
                ),
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
                description = "End the task. Use it as soon as the goal is reached, blocked, or needs the user. An owed result must be confirmed delivered first.",
                parameters = schema(listOf("summary"), "summary" to stringProp("what happened, in one or two sentences")),
            )
        )
        add(
            ToolSpec(
                name = "confirm_delivered",
                description = "Close an owed result: call it after the promised message is really sent and visible in the conversation, with the exact text you typed as the result. Verified against the screen; a second call is taken on faith.",
                parameters = schema(listOf("result"), "result" to stringProp("the exact text of the delivered result message")),
            )
        )
        add(
            ToolSpec(
                name = "record_offer",
                description = "Record one option you found (a flight, a room, a plan) with its price. Record every candidate from every site or app you check — at least three when possible — then compare_offers before booking anything. Never book the first result.",
                parameters = schema(
                    listOf("name", "price"),
                    "name" to stringProp("offer name as shown, e.g. \"Turkish Airlines Basic\""),
                    "price" to stringProp("the price as printed, digits only; omit when the screen hides it"),
                    "currency" to stringProp("optional currency code or symbol, e.g. EUR or $"),
                    "details" to stringProp("optional what is included: stops, baggage, refundability"),
                ),
            )
        )
        add(
            ToolSpec(
                name = "compare_offers",
                description = "Rank the recorded offers, cheapest first. Call it before booking; book the cheapest that meets the user's constraints and say the price you chose.",
                parameters = schema(),
            )
        )
        add(
            ToolSpec(
                name = "create_site",
                description = "Start a real website project: a directory of files on the phone named after the site. Use it before write_site_file.",
                parameters = schema(listOf("name"), "name" to stringProp("site name, e.g. \"Cafe Luna\"")),
            )
        )
        add(
            ToolSpec(
                name = "write_site_file",
                description = "Write one file of the website, for example index.html or style.css. Write complete, clean, responsive HTML/CSS with one palette, generous spacing and real content from the user's brief.",
                parameters = schema(
                    listOf("path", "content"),
                    "path" to stringProp("file path inside the site, e.g. index.html or pages/about.html"),
                    "content" to stringProp("the complete file content"),
                ),
            )
        )
        add(
            ToolSpec(
                name = "download_image",
                description = "Download one image from the web into the site's images folder so HTML can show it. Prefer stable direct image URLs and reference the returned path as images/<file>.",
                parameters = schema(
                    listOf("url"),
                    "url" to stringProp("direct link to the image file"),
                    "fileName" to stringProp("optional file name, e.g. hero"),
                ),
            )
        )
        add(
            ToolSpec(
                name = "list_site_files",
                description = "List the files the site has so far, with sizes.",
                parameters = schema(),
            )
        )
        add(
            ToolSpec(
                name = "create_doc",
                description = "Start a document the user asked for (book, whitepaper, report): a project of written sections. Use before write_doc_section.",
                parameters = schema(listOf("name"), "name" to stringProp("document title, e.g. \"AI in Schools\"")),
            )
        )
        add(
            ToolSpec(
                name = "write_doc_section",
                description = "Write one section of the document with its full real content — never a placeholder or outline. The body is the actual prose of the chapter, however long it needs to be.",
                parameters = schema(
                    listOf("title", "body"),
                    "title" to stringProp("section title, e.g. \"Chapter 1: Origins\""),
                    "body" to stringProp("the section's full text"),
                ),
            )
        )
        add(
            ToolSpec(
                name = "list_doc_sections",
                description = "List the document's sections and rendered files so far.",
                parameters = schema(),
            )
        )
        add(
            ToolSpec(
                name = "render_doc",
                description = "Render the finished document as a real file (docx or pdf). It appears in the chat with a download button; say so in the finish summary.",
                parameters = schema(emptyList(), "format" to stringProp("file format", listOf("docx", "pdf"))),
            )
        )
        add(
            ToolSpec(
                name = "preview_site",
                description = "Serve the site from the phone and open it in the browser to check the real look. Polish what looks wrong, then finish with the local address.",
                parameters = schema(),
            )
        )
        add(
            ToolSpec(
                name = "close_site_preview",
                description = "Stop the local preview server once the site is delivered.",
                parameters = schema(),
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
                "tap_text" -> ToolOutcome(
                    executor.tapText(requireString(args, "text"), JsonArgs.int(args, "occurrence") ?: 1)
                )
                "find_text" -> ToolOutcome(executor.findText(requireString(args, "query")), includeScreen = false)
                "long_press" -> ToolOutcome(
                    executor.longPress(JsonArgs.int(args, "index"), JsonArgs.string(args, "query"))
                )
                "press_enter" -> ToolOutcome(executor.pressEnter())
                "scroll_to_text" -> ToolOutcome(
                    executor.scrollToText(
                        requireString(args, "query"),
                        JsonArgs.string(args, "direction") ?: "down",
                        JsonArgs.int(args, "max_swipes") ?: 6,
                    )
                )
                "scroll_in" -> ToolOutcome(
                    executor.scrollIn(requireInt(args, "index"), requireString(args, "direction"))
                )
                "wait_for_text" -> ToolOutcome(
                    executor.waitForText(
                        requireString(args, "query"),
                        (JsonArgs.int(args, "timeout_seconds") ?: 20) * 1000,
                    )
                )
                "app_skill" -> ToolOutcome(appSkill(executor, JsonArgs.string(args, "app")), includeScreen = false)
                "save_skill" -> ToolOutcome(saveSkill(args), includeScreen = false)
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
                    brain.rememberFact(key, value)
                    ToolOutcome("Remembered: $key.", includeScreen = false)
                }
                "remember_person" -> {
                    val name = requireString(args, "name")
                    memory.rememberPerson(
                        name,
                        JsonArgs.string(args, "relation").orEmpty(),
                        JsonArgs.string(args, "channel").orEmpty(),
                        JsonArgs.string(args, "note").orEmpty(),
                    )
                    ToolOutcome("Noted what you know about $name.", includeScreen = false)
                }
                "recall" -> ToolOutcome(memory.render(), includeScreen = false)
                "forget" -> {
                    val key = requireString(args, "key")
                    val removed = memory.removeFact(key)
                    if (removed) brain.forgetFact(key)
                    ToolOutcome(
                        if (removed) "Forgot it." else "No fact with that key. Call recall to see the exact keys.",
                        includeScreen = false,
                    )
                }
                "clear_memory" -> {
                    val keys = memory.snapshot().keys.toList()
                    val count = memory.clearFacts()
                    keys.forEach { brain.forgetFact(it) }
                    ToolOutcome(
                        "Cleared $count fact${if (count == 1) "" else "s"}. People and past tasks are kept.",
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
                "record_offer" -> {
                    val saved = board.record(
                        requireString(args, "name"),
                        offerPrice(args),
                        JsonArgs.string(args, "currency").orEmpty(),
                        JsonArgs.string(args, "details").orEmpty(),
                    )
                    val price = if (saved.price == null) "unknown price" else "${saved.price}${boardLabel(saved.currency)}"
                    ToolOutcome("Recorded offer \"${saved.name}\" at $price.", includeScreen = false)
                }
                "compare_offers" -> ToolOutcome(board.compare(), includeScreen = false)
                "create_site" -> {
                    val dir = workspace.open(requireString(args, "name"))
                    ToolOutcome(
                        "Site directory ready (${dir.name}). Build it with write_site_file (index.html, style.css), " +
                            "decorate with download_image, then check the real look with preview_site.",
                        includeScreen = false,
                    )
                }
                "write_site_file" -> {
                    val relative = workspace.writeFile(
                        JsonArgs.string(args, "site"),
                        requireString(args, "path"),
                        requireString(args, "content"),
                    )
                    ToolOutcome("Wrote $relative.", includeScreen = false)
                }
                "download_image" -> ToolOutcome(
                    downloadIntoSite(
                        JsonArgs.string(args, "site"),
                        requireString(args, "url"),
                        JsonArgs.string(args, "fileName").orEmpty(),
                    )
                )
                "list_site_files" -> ToolOutcome(workspace.list(JsonArgs.string(args, "site")), includeScreen = false)
                "preview_site" -> {
                    val port = siteServer.serve(workspace.dir(JsonArgs.string(args, "site")))
                    val url = "http://127.0.0.1:$port/"
                    executor.openUrl(url)
                    ToolOutcome("Serving the site at $url — it just opened in the browser. Fix what looks wrong, then finish with this address.")
                }
                "close_site_preview" -> {
                    siteServer.stop()
                    ToolOutcome("Preview server stopped.", includeScreen = false)
                }
                "create_doc" -> {
                    val dir = docs.open(requireString(args, "name"))
                    ToolOutcome(
                        "Document \"${docs.name()}\" started (${dir.name}). Write it with write_doc_section — " +
                            "one call per chapter or section, each with the full real text.",
                        includeScreen = false,
                    )
                }
                "write_doc_section" -> {
                    val title = requireString(args, "title")
                    docs.addSection(title, requireString(args, "body"))
                    ToolOutcome(
                        "Section \"$title\" added (${docs.sections().size} total). Keep going until the whole work is written.",
                        includeScreen = false,
                    )
                }
                "list_doc_sections" -> ToolOutcome(docs.list(), includeScreen = false)
                "render_doc" -> {
                    val format = (JsonArgs.string(args, "format") ?: "pdf").lowercase()
                    require(format == "pdf" || format == "docx") { "format must be \"docx\" or \"pdf\"" }
                    dev.humanagent.doc.Pdf.ensureInit(context)
                    val file = docs.render(format)
                    val mime = if (format == "docx") {
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    } else {
                        "application/pdf"
                    }
                    val count = docs.sections().size
                    dev.humanagent.HumanPhoneApp.instance.chatEngine.attachResult(
                        file,
                        mime,
                        "Done — ${file.name} ($count sections) is ready. Use the download button to save it.",
                    )
                    ToolOutcome(
                        "Rendered ${file.name} ($count sections) and attached it to the chat with a download button. Call finish and name the file.",
                        includeScreen = false,
                    )
                }
                "confirm_delivered" -> confirmDelivered(requireString(args, "result"), executor)
                "finish" -> {
                    val summary = requireString(args, "summary")
                    // The contract the task opened with is still standing: the result exists
                    // and was never confirmed in the conversation it was promised to. finish
                    // is refused twice so the model goes and sends it; the third call ends
                    // the run and the watchdog picks the delivery up from the ledger.
                    val owed = owedObligation()
                    if (owed != null && (refusedFinishes[owed.id] ?: 0) < MAX_FINISH_REFUSALS) {
                        val count = (refusedFinishes[owed.id] ?: 0) + 1
                        refusedFinishes[owed.id] = count
                        return ToolOutcome(
                            "Not done: \"${owed.result.take(120)}\" was never sent to ${owed.destination}" +
                                " in ${owed.app.ifBlank { "its app" }}. Reach that conversation, send it as a message," +
                                " then call confirm_delivered with the exact text. Finish was refused $count of $MAX_FINISH_REFUSALS.",
                            includeScreen = true,
                        )
                    }
                    // The task is over, the phone must come back to where the assistant lives:
                    // a messaging app left open in front suppresses its notifications, and the
                    // watchdog would go deaf to the very conversation it just ran in.
                    runCatching {
                        executor.globalAction("home")
                        executor.openApp("HumanPhone")
                    }
                    ToolOutcome(summary, terminal = true, includeScreen = false)
                }
                else -> ToolOutcome(
                    "There is no tool called \"${call.name}\". Available tools: " +
                        specs.joinToString(", ") { it.name },
                    includeScreen = false,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            ToolOutcome("Bad arguments for ${call.name}: ${e.message}", includeScreen = false)
        } catch (e: Exception) {
            ToolOutcome(
                "${call.name} did not go through: ${e.javaClass.simpleName}${e.message?.let { ": $it" }.orEmpty()}",
                includeScreen = false,
            )
        }
    }

    /** The open owed-result obligation this run is bound to, read fresh from the ledger. */
    private fun owedObligation(): Obligation? {
        val active = obligation ?: return null
        val entry = ledger.current(active.app, active.destination) ?: return null
        return entry.takeIf { !it.delivered && it.result.isNotBlank() }
    }

    /**
     * Closes an owed result. The first call is checked against the real screen: the message
     * must be visible where it was promised. Only after a second call — the model insisting —
     * is it accepted unseen, and the ledger says so in the episode.
     */
    private suspend fun confirmDelivered(result: String, executor: UiActionExecutor): ToolOutcome {
        val owed = owedObligation()
            ?: return ToolOutcome("Nothing is owed right now; confirm_delivered closes a promised result and there is none.", includeScreen = false)
        ledger.setResult(owed.id, result)
        val probe = result.trim().take(60)
        val seen = probe.isNotBlank() && executor.sees(probe)
        val attempts = (confirmations[owed.id] ?: 0) + 1
        confirmations[owed.id] = attempts
        return when {
            seen -> {
                ledger.markDelivered(owed.id)
                ToolOutcome("Verified: \"$probe\" is visible on screen. The result was delivered.", includeScreen = false)
            }
            attempts >= 2 -> {
                ledger.markDelivered(owed.id)
                ToolOutcome(
                    "Taken as delivered after your second confirmation — I could not see \"$probe\" on screen myself.",
                    includeScreen = false,
                )
            }
            else -> ToolOutcome(
                "I do not see \"$probe\" on this screen. If it has not really been sent, send it into ${owed.destination} first;" +
                    " if it has, call confirm_delivered once more and I will accept it without watching.",
                includeScreen = true,
            )
        }
    }

    /** The built-in manual plus any stored procedure for an app, or a plain answer when none is known. */
    private fun appSkill(executor: UiActionExecutor, query: String?): String {
        val foreground = executor.foregroundApp()
        val skill = if (query.isNullOrBlank()) {
            AppSkills.forApp(foreground.label, foreground.packageName)
        } else {
            AppSkills.forQuery(query)
        }
        val label = skill?.appName ?: query?.trim().orEmpty().ifEmpty { foreground.label }
        val packageName = skill?.packages?.firstOrNull()
            ?: if (query.isNullOrBlank()) foreground.packageName else ""
        val learned = if (query.isNullOrBlank()) {
            skills.render(foreground.label, foreground.packageName) ?: skills.render(label, packageName)
        } else {
            skills.render(query, query) ?: skills.render(label, packageName)
        }
        if (skill == null && learned == null) {
            return if (query.isNullOrBlank()) {
                "I have no notes for ${foreground.label.ifBlank { foreground.packageName.ifBlank { "the app on screen" } }}" +
                    " (${foreground.packageName.ifBlank { "unknown package" }}); store what works with save_skill."
            } else {
                "I have no notes for \"$query\"; store what works with save_skill and it will be here next time."
            }
        }
        return buildString {
            if (skill != null) append(AppSkills.render(skill))
            if (learned != null) {
                if (isNotEmpty()) append('\n')
                append(learned)
            }
        }
    }

    private suspend fun saveSkill(args: String): String {
        val app = requireString(args, "app")
        val steps = stringList(args, "steps")
        if (steps.isEmpty()) return "save_skill needs at least one step; nothing was stored."
        skills.save(app, JsonArgs.string(args, "package").orEmpty(), steps)
        return "Stored ${steps.size} step${if (steps.size == 1) "" else "s"} for $app; they come back with app_skill."
    }

    /** Reads a price tolerating "289", "289.50", "€289,50" and "289,50 EUR". */
    private fun offerPrice(args: String): Double? =
        JsonArgs.asObject(args)?.get("price")?.let { value ->
            if (value is JsonNull) {
                null
            } else {
                (value as? JsonPrimitive)?.content?.trim()
                    ?.replace(',', '.')?.replace(Regex("[^0-9.]"), "")?.toDoubleOrNull()
            }
        }

    private fun boardLabel(currency: String): String = if (currency.isBlank()) "" else " $currency"

    /** Downloads one image over HTTP and stores it in the site's images folder. */
    private fun downloadIntoSite(site: String?, url: String, fileName: String): String {
        val scheme = url.trim().substringBefore(':').lowercase()
        require(scheme == "http" || scheme == "https") { "download_image only takes http or https links" }
        val request = downloadClient.newCall(
            Request.Builder().url(url).build(),
        ).execute().use { fetched ->
            require(fetched.isSuccessful) { "download failed with HTTP ${fetched.code}" }
            val body = fetched.body ?: throw IllegalArgumentException("empty download")
            val type = body.contentType()
            require(type == null || type.type == "image" || type.type == "application/octet-stream") {
                "the link serves ${type ?: "unknown"} content, not an image"
            }
            val bytes = body.bytes()
            require(bytes.size <= 10 * 1024 * 1024) { "the file is larger than 10 MB" }
            val extension = when {
                fileName.contains('.') -> fileName.substringAfterLast('.')
                type != null && type.type == "image" -> type.subtype
                else -> "jpg"
            }
            val base = fileName.trim().ifEmpty { "image" }
                .substringBeforeLast('/')
                .substringBeforeLast('.')
                .take(60)
                .ifEmpty { "image" }
            return workspace.saveImage(site, base, bytes, extension)
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

    private fun stringProp(description: String, options: List<String>? = null): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
        if (options != null) {
            putJsonArray("enum") { options.forEach { add(it) } }
        }
    }

    private fun intProp(description: String): JsonObject = buildJsonObject {
        put("type", "integer")
        put("description", description)
    }

    private fun stringArrayProp(description: String): JsonObject = buildJsonObject {
        put("type", "array")
        put("description", description)
        putJsonObject("items") { put("type", "string") }
    }

    /** Reads a string list, tolerating one string with newline or semicolon separated steps. */
    private fun stringList(args: String, key: String): List<String> {
        val element = JsonArgs.asObject(args)?.get(key) ?: return emptyList()
        val raw = when {
            element is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.content }
            element is JsonNull -> emptyList()
            element is JsonPrimitive -> element.content.split('\n', ';')
            else -> emptyList()
        }
        return raw.map { it.trim().removePrefix("-").trim() }.filter { it.isNotEmpty() }.take(40)
    }

    /** The shared OkHttp client for everything the loop itself downloads: strict and small. */
    private val downloadClient: OkHttpClient =
        LlmClient.sharedClient.newBuilder()
            // A redirect could land on anything — a different host, a cleartext URL, an address
            // the user never saw. One hop of trust only: the link the model gave is the link.
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

    companion object {
        /** How often finish is refused while the owed result has not been confirmed. */
        private const val MAX_FINISH_REFUSALS = 2
    }
}
