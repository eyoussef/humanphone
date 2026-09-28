package dev.humanagent.agent

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * App-specific knowledge the model cannot guess from a single screen dump: where the bars live,
 * which strips scroll sideways, what the buttons read, and which traps to avoid.
 */
data class AppSkill(
    val appName: String,
    val packages: List<String>,
    val labels: List<String>,
    val notes: List<String>,
)

/** A procedure the user or the agent worked out once, kept so the next run does not relearn it. */
@Serializable
data class LearnedSkill(
    val app: String = "",
    val packageName: String = "",
    val steps: List<String> = emptyList(),
    val updatedAtMs: Long = 0L,
)

/**
 * The built-in manual for the apps people actually ask the assistant to drive. Notes are written
 * for an accessibility-driven agent: they name labels, scroll strips, buttons and known traps.
 */
object AppSkills {

    val builtIn: List<AppSkill> = listOf(
        AppSkill(
            appName = "Canva",
            packages = listOf("com.canva.editor"),
            labels = listOf("canva"),
            notes = listOf(
                "The home screen has a bottom toolbar that scrolls sideways: Home, Canva AI, Templates, Elements, Text, Uploads, Photos, Styles, Brand, More.",
                "Only the tiles currently on screen appear in the screen dump; when \"Canva AI\" is missing, swipe that toolbar left with scroll_in on the toolbar element index, or scroll_to_text \"Canva AI\", until it appears.",
                "Tap \"Canva AI\" — it can read \"Ask Canva AI\" or \"Magic\" — to open the prompt sheet.",
                "Tap the prompt box that reads \"Describe the design you want to create\" or \"Ask anything\".",
                "type_text the prompt, then submit with press_enter or by tapping the send arrow / \"Generate\" button at the bottom right of the sheet; never submit with back.",
                "Then wait_for_text for \"Edit\" or \"Download\" (up to 60 seconds) instead of tapping blindly.",
                "Keep the result by tapping the generated card and then \"Edit\", \"Download\" or \"Share\".",
                "Trap: back closes the prompt sheet and loses the prompt — reopen the sheet instead of giving up.",
                "Trap: a paywall or sign-in screen can appear; report it with finish rather than looping.",
            ),
        ),
        AppSkill(
            appName = "WhatsApp",
            packages = listOf("com.whatsapp", "com.whatsapp.w4b"),
            labels = listOf("whatsapp"),
            notes = listOf(
                "Chats list: the search field at the top reads \"Search\", and the bottom bar reads Chats, Updates, Communities, Calls.",
                "In a chat, the message field at the bottom reads \"Message\"; the send button only appears once text is typed, so submit with press_enter.",
                "Attach with the paperclip or plus button on the left of the message field, then pick Gallery, Camera, Document or Contact from the sheet.",
                "After picking media, tap the green send arrow at the bottom right; captions go in the field reading \"Add a caption\".",
                "The compose button for a new chat is the \"New chat\" button at the bottom right; pick a contact row, then type in the message field.",
                "Long-press a message to reach Reply, Copy, Forward, Star and Delete in the popup.",
                "Trap: the contact picker and the attachment sheet appear as separate windows — look at the whole dump, not only the first window.",
            ),
        ),
        AppSkill(
            appName = "Instagram",
            packages = listOf("com.instagram.android", "com.instagram.lite"),
            labels = listOf("instagram"),
            notes = listOf(
                "The bottom bar holds Home, Search, Create (+) and Profile; it does not scroll.",
                "Search is its own screen with a field reading \"Search\"; type a name and then tap the account row.",
                "The story strip at the top of the home feed scrolls sideways — swipe inside it to reach later stories.",
                "Feed and Reels scrolling needs a vertical swipe on the content, never on the bottom bar.",
                "To post: Create (+) → pick media → \"Next\" at the top right → fill \"Write a caption\" → \"Share\".",
                "Profile grid cells carry no text, so use the screenshot and tap the cell centre.",
                "Trap: the story camera opens full screen with almost no labels; back out and use Create from the bottom bar instead.",
            ),
        ),
        AppSkill(
            appName = "Telegram",
            packages = listOf("org.telegram.messenger", "org.telegram.messenger.web"),
            labels = listOf("telegram"),
            notes = listOf(
                "Chats list: the search field at the top reads \"Search\"; the pencil compose button sits at the bottom right.",
                "In a chat the message field reads \"Message\"; the paper-plane send button appears once text is typed, and press_enter also sends.",
                "Attach with the paperclip inside the message field, then choose Gallery, File, Location or Contact.",
                "Folders appear as a tab strip under the search field and scroll sideways — swipe that strip, not the chat list, to reach later folders.",
                "The hamburger at the top left opens the sidebar with Settings, Contacts and Saved Messages.",
                "Long-press a chat for Pin, Mute, Archive and Delete.",
            ),
        ),
        AppSkill(
            appName = "Gmail",
            packages = listOf("com.google.android.gm"),
            labels = listOf("gmail"),
            notes = listOf(
                "The compose button is the \"Compose\" button at the bottom right of the mail list.",
                "Compose is a sheet with fields reading \"To\", \"Subject\" and the body; press_enter inside \"To\" picks the suggested address.",
                "The send button is at the top right of the compose sheet and reads \"Send\".",
                "The search field at the top reads \"Search in mail\"; press_enter runs the search.",
                "Long-press a message row to select it, then use the top bar actions such as Archive, Delete or Mark read.",
                "Trap: the compose sheet is a separate window — read the whole dump before typing, the fields may not be in the first window you see.",
                "The account switcher is the avatar at the top right of the list.",
            ),
        ),
        AppSkill(
            appName = "YouTube",
            packages = listOf("com.google.android.youtube"),
            labels = listOf("youtube"),
            notes = listOf(
                "The bottom bar holds Home, Shorts, Create (+), Subscriptions and You; it does not scroll.",
                "Search is the magnifier at the top right; its field reads \"Search YouTube\" and press_enter runs the query.",
                "Result rows are clickable containers with a title; tap the title or thumbnail.",
                "Subscribing is the \"Subscribe\" button under the player; the action row under the video scrolls sideways to reach Share and Save.",
                "Comments and the description are below the player — scroll the page down with a vertical swipe.",
                "Trap: Shorts are a full-screen vertical swipe surface with almost no labels; drive them with screen swipes.",
            ),
        ),
        AppSkill(
            appName = "Chrome",
            packages = listOf("com.android.chrome", "com.chrome.beta"),
            labels = listOf("chrome"),
            notes = listOf(
                "The address bar at the top is the field reading \"Search or type URL\"; type and press_enter to navigate.",
                "Tabs are the number box at the top right; \"New tab\" is the plus next to it.",
                "The menu at the top right holds New tab, History, Downloads and Settings.",
                "Page content belongs to the website: if the dump has no useful labels, take the screenshot and tap what you see.",
                "Consent and cookie dialogs are their own window; tap the labelled button such as \"Accept all\" instead of guessing coordinates.",
                "The bottom bar, when enabled, holds Back, Home, Tabs and Menu.",
            ),
        ),
        AppSkill(
            appName = "Settings",
            packages = listOf("com.android.settings", "com.google.android.settings"),
            labels = listOf("settings"),
            notes = listOf(
                "The search field at the top reads \"Search settings\".",
                "Rows are labelled with their title, for example \"Network & internet\", \"Apps\", \"Display\"; tap the row title.",
                "Sub-screens use the back arrow at the top left; the global navigate back action works too.",
                "Toggles are switches labelled with the setting name and report checked or unchecked in the dump.",
                "Per-app controls are reached through Apps → app name → permissions or battery.",
                "Trap: some screens hide later options behind a horizontal tab strip; swipe that strip when a tab is missing from the dump.",
            ),
        ),
        AppSkill(
            appName = "Photos",
            packages = listOf("com.google.android.apps.photos"),
            labels = listOf("photos"),
            notes = listOf(
                "The bottom bar holds Photos, Collections and Search; it does not scroll.",
                "Search's field reads \"Search your photos\"; press_enter runs it.",
                "Tap a photo to open the viewer, which shows few labels; use the screenshot and the top bar for Share, Delete and Move.",
                "Sharing opens a sheet of labelled target apps, with \"Send\" at the top right.",
                "Long-press a thumbnail to select it, then use the top bar actions Share, Add to or Delete.",
                "Trap: selecting photos adds a top toolbar and shifts the dump; read the screen again before the next tap.",
            ),
        ),
        AppSkill(
            appName = "Files",
            packages = listOf("com.google.android.documentsui", "com.android.documentsui", "com.google.android.apps.nbu.files"),
            labels = listOf("files", "documentsui"),
            notes = listOf(
                "The hamburger at the top left lists Downloads, Images and Videos as labelled rows.",
                "Download rows are labelled with the file name; the row's menu holds Open with, Rename and Delete.",
                "Search is the magnifier in the top bar and its field reads \"Search\".",
                "Copy and move flows ask for a destination folder and then a labelled \"Move\" or \"Copy\" button.",
                "Trap: pick-a-file sheets opened by other apps are the Files window; they show up as a second window in the dump.",
            ),
        ),
        AppSkill(
            appName = "Clock",
            packages = listOf("com.google.android.deskclock", "com.android.deskclock"),
            labels = listOf("clock"),
            notes = listOf(
                "The bottom tabs read Alarm, Clock, Timer and Stopwatch.",
                "The add button, labelled \"Add alarm\" or \"+\", sits at the bottom right.",
                "In the alarm editor the hour and minute fields are labelled; the confirm button reads \"OK\" or \"Save\".",
                "Timer's start button reads \"Start\" and becomes \"Pause\" or \"Reset\" while running.",
                "Trap: the alarm picker is a clock face whose numbers are labels; typing into the hour and minute fields is more reliable.",
            ),
        ),
        AppSkill(
            appName = "Messages",
            packages = listOf("com.google.android.apps.messaging", "com.samsung.android.messaging"),
            labels = listOf("messages", "messaging"),
            notes = listOf(
                "Conversation list: the search field reads \"Search\" and the new-chat button sits at the bottom right.",
                "In a chat the field reads \"Text message\"; the send button appears on the right once text is typed, and press_enter also sends.",
                "Attach with the plus or paperclip at the left of the field, then pick Gallery, Camera, Location or Contact.",
                "A new conversation is created by the \"Start chat\" button → contact row → \"Next\" → type → send.",
                "Trap: a first-time RCS or permission dialog can take focus; handle or dismiss it before typing.",
            ),
        ),
        AppSkill(
            appName = "Play Store",
            packages = listOf("com.android.vending"),
            labels = listOf("play store", "google play"),
            notes = listOf(
                "The bottom bar holds Games, Apps and Books; search is the magnifier at the top right.",
                "The search field reads \"Search for apps & games\"; press_enter submits the query.",
                "On an app page the primary button reads \"Install\" and then \"Open\" or \"Cancel\"; progress is shown as a percentage.",
                "Reviews are reached by scrolling the app page down, and the \"See all reviews\" row is labelled.",
                "Trap: paid apps and some installs open a confirmation sheet with \"OK\" or a price button that must be tapped again.",
            ),
        ),
        AppSkill(
            appName = "Spotify",
            packages = listOf("com.spotify.music"),
            labels = listOf("spotify"),
            notes = listOf(
                "The bottom bar holds Home, Search and Your Library, plus an upgrade entry; it does not scroll.",
                "Search's field reads \"What do you want to listen to?\"; press_enter runs the query and results appear as labelled rows.",
                "The mini player sits above the bottom bar; tap it to open the now-playing screen.",
                "On the now-playing screen the play and pause button is labelled \"Play\" or \"Pause\"; \"Shuffle\" and \"Repeat\" sit beside it.",
                "Trap: the queue and the device picker open as bottom sheets — read the whole dump before tapping.",
            ),
        ),
        AppSkill(
            appName = "Google Maps",
            packages = listOf("com.google.android.apps.maps"),
            labels = listOf("maps"),
            notes = listOf(
                "The search box at the top reads \"Search here\" or \"Search Maps\"; type a place and press_enter.",
                "A place sheet has a labelled action row with \"Directions\", \"Save\", \"Share\" and \"Call\" that can scroll sideways.",
                "The blue \"Directions\" pill on a place sheet starts routing.",
                "On the route screen the car, transit and walk options are a tab strip at the top.",
                "Trap: the map canvas has no useful labels; act on search results and sheet buttons instead of coordinates.",
            ),
        ),
        AppSkill(
            appName = "Google Docs",
            packages = listOf("com.google.android.apps.docs.editors.docs", "com.google.android.apps.docs"),
            labels = listOf("docs", "google docs"),
            notes = listOf(
                "The document list is a separate window and its rows are labelled with the document title.",
                "The plus button in the list creates a blank document.",
                "The editor itself is a canvas: the dump shows almost no labels, so place the cursor with a tap and then use type_text and press_enter.",
                "Sharing is the person or share button in the top bar, and \"Share\" then appears as a labelled row.",
                "Trap: type_text only works after the page has been tapped so the cursor is placed.",
            ),
        ),
        AppSkill(
            appName = "X",
            packages = listOf("com.twitter.android"),
            labels = listOf("x", "twitter"),
            notes = listOf(
                "The bottom bar holds Home, Search, Grok or Create, Notifications and DMs; newer builds let it scroll sideways, so check the dump before assuming.",
                "Compose is the \"+\" button at the bottom right; the compose screen's field reads \"What is happening?!\".",
                "Submit a post with the \"Post\" button at the top right of the compose screen, never with back.",
                "Search's field reads \"Search\"; press_enter runs it and results have a top, latest and people tab strip.",
                "Post actions sit in a row under the post: \"Reply\", \"Repost\", \"Like\" and \"Share\".",
                "Trap: X opens external links and profile tabs as separate windows; read the whole dump.",
            ),
        ),
    )

    /** The built-in manual for the app on screen, matched by package prefix first and label second. */
    fun forApp(appLabel: String, packageName: String): AppSkill? {
        val pkg = packageName.trim().lowercase()
        val label = appLabel.trim().lowercase()
        if (pkg.isNotEmpty()) {
            builtIn.firstOrNull { skill -> skill.packages.any { pkg.startsWith(it) || it.startsWith(pkg) } }?.let { return it }
        }
        if (label.isEmpty()) return null
        return builtIn.firstOrNull { skill -> skill.labels.any { matchesKeyword(label, it) } }
    }

    /** The built-in manual for a name the model wrote, for example "Canva" or "com.canva.editor". */
    fun forQuery(query: String): AppSkill? {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return null
        val distinctive = needle.length >= 3
        return builtIn.firstOrNull { skill ->
            skill.packages.any {
                it == needle || it.startsWith(needle) || needle.startsWith(it) || (distinctive && it.contains(needle))
            } ||
                skill.labels.any { it == needle || (distinctive && it.contains(needle)) } ||
                (distinctive && skill.appName.lowercase().contains(needle))
        }
    }

    /** The manual as text: "Canva (com.canva.editor):" followed by one note per line. */
    fun render(skill: AppSkill): String = buildString {
        append(skill.appName)
        append(" (")
        append(skill.packages.firstOrNull().orEmpty())
        append("):")
        skill.notes.forEach { note -> append("\n- ").append(note) }
    }

    /** One-letter labels such as "x" only match a whole app name, longer ones match as substrings. */
    private fun matchesKeyword(label: String, keyword: String): Boolean =
        if (keyword.length < 3) label == keyword else label.contains(keyword)
}

/**
 * Procedures learned at runtime, stored beside the memory file. Same tolerant style as
 * [MemoryStore]: a corrupt file is ignored, never fatal.
 */
class SkillStore(private val file: File) {

    private val mutex = Mutex()
    private val skills = LinkedHashMap<String, LearnedSkill>()

    constructor(context: Context) : this(File(context.filesDir, "skills.json"))

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            skills.clear()
            if (file.exists()) {
                val raw = runCatching { file.readText() }.getOrNull()
                val parsed = raw?.let { runCatching { json.decodeFromString<List<LearnedSkill>>(it) }.getOrNull() }
                parsed?.forEach { skill -> skills[keyOf(skill.app, skill.packageName)] = skill }
            }
        }
    }

    /** Replaces what is known for this app with the freshly learned steps. */
    suspend fun save(app: String, packageName: String, steps: List<String>) = withContext(Dispatchers.IO) {
        val cleanApp = app.trim().take(60)
        val cleanPackage = packageName.trim().take(120)
        val cleanSteps = steps.map { it.trim().take(200) }.filter { it.isNotEmpty() }.take(40)
        if (cleanSteps.isEmpty()) return@withContext
        mutex.withLock {
            skills[keyOf(cleanApp, cleanPackage)] = LearnedSkill(
                app = cleanApp,
                packageName = cleanPackage,
                steps = cleanSteps,
                updatedAtMs = System.currentTimeMillis(),
            )
            persistLocked()
        }
    }

    fun snapshot(): List<LearnedSkill> = skills.values.toList()

    /** The learned steps for this app, or null when nothing was stored for it yet. */
    fun render(appLabel: String, packageName: String): String? {
        val label = appLabel.trim().lowercase()
        val pkg = packageName.trim().lowercase()
        if (label.isEmpty() && pkg.isEmpty()) return null
        val matches = skills.values.filter { skill ->
            val storedPackage = skill.packageName.trim().lowercase()
            val storedApp = skill.app.trim().lowercase()
            (pkg.isNotEmpty() && storedPackage.isNotEmpty() &&
                (storedPackage == pkg || storedPackage.startsWith(pkg) || pkg.startsWith(storedPackage))) ||
                (label.isNotEmpty() && storedApp.isNotEmpty() && (storedApp == label || storedApp.startsWith(label)))
        }
        if (matches.isEmpty()) return null
        return matches.joinToString("\n") { skill ->
            "Learned steps for ${skill.app.ifBlank { skill.packageName }}: " + skill.steps.joinToString(" → ")
        }
    }

    private fun keyOf(app: String, packageName: String): String =
        packageName.trim().ifBlank { app.trim() }.lowercase()

    private fun persistLocked() {
        runCatching { file.writeText(json.encodeToString(skills.values.toList())) }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    }
}
