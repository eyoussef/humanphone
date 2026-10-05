package dev.humanagent.agent

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Path
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay

data class AppEntry(val label: String, val packageName: String)

/**
 * Everything the assistant can physically do on the phone. Each method returns a short sentence
 * describing what happened, which is fed straight back to the model as the tool result.
 */
class UiActionExecutor(
    private val service: AgentAccessibilityService,
    private val reader: ScreenReader,
    /**
     * The user's explicit opt-in for sending texts without a composer stop. Default off — the
     * composer path is the safe default and needs no SMS permission at all.
     */
    private val directSms: Boolean = false,
) {

    private val appsCache = AtomicReference<List<AppEntry>?>(null)

    suspend fun tapIndex(index: Int): String {
        val node = reader.locate(index)
            ?: return "Nothing at index $index any more; the screen changed."
        return tapNode(node)
    }

    /**
     * Taps the [occurrence]-th element containing [query], exact labels first. When there is no
     * exact match but several elements contain the text, the answer says how many did.
     */
    suspend fun tapText(query: String, occurrence: Int = 1): String {
        val matches = reader.findMatches(query)
        if (matches.isEmpty()) return "No visible element contains \"$query\"."
        val wanted = occurrence.coerceAtLeast(1)
        if (wanted > matches.size) {
            return "${matches.size} element${if (matches.size == 1) "" else "s"} contain " +
                "\"$query\", but number $wanted was asked for; use find_text to see them."
        }
        val match = matches[wanted - 1]
        val tapped = tapNode(match.node)
        val exact = match.label.trim().equals(query.trim(), ignoreCase = true)
        return if (!exact && matches.size > 1) {
            "$tapped (no exact match; ${matches.size} elements contain \"$query\")"
        } else {
            tapped
        }
    }

    suspend fun tapDescription(query: String): String {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return "Empty description."
        val node = reader.findByText(needle)
            ?: return "No element with description \"$query\"."
        return tapNode(node)
    }

    /** Lists up to eight elements containing [query], in the order tap_text would pick them. */
    fun findText(query: String): String {
        val matches = reader.findMatches(query)
        if (matches.isEmpty()) return "No visible element contains \"$query\"."
        return matches.joinToString("\n") { match ->
            val node = match.node
            val (x, y) = reader.centerOf(node)
            val flags = buildString {
                if (runCatching { node.isClickable }.getOrDefault(false)) append(" clickable")
                if (runCatching { node.isEditable }.getOrDefault(false)) append(" editable")
                if (runCatching { node.isScrollable }.getOrDefault(false)) append(" scrollable")
            }
            "[${match.index}] ${classNameOf(node)} \"${match.label.take(80)}\" @$x,$y$flags"
        }
    }

    /**
     * True when some element on the current screen shows [query]; the evidence a promised
     * message needs before confirm_delivered closes an obligation.
     */
    fun sees(query: String): Boolean = reader.findMatches(query, 1).isNotEmpty()

    /**
     * Scrolls until [query] is on screen: every swipe goes to the container most likely to hold it,
     * and the screen is re-read after each one. Returns the fresh index when it is found.
     */
    suspend fun scrollToText(query: String, direction: String = "down", maxSwipes: Int = 6): String {
        val needle = query.trim()
        if (needle.isEmpty()) return "Empty text."
        val way = direction.trim().lowercase()
        if (way !in SWIPE_DIRECTIONS) return "Unknown direction \"$direction\"; use up, down, left or right."
        reader.findMatches(needle, 1).firstOrNull()?.let {
            return "\"$needle\" is already visible at index ${it.index}."
        }
        val swipes = maxSwipes.coerceIn(1, 12)
        var performed = 0
        while (performed < swipes) {
            scrollContainerOrScreen(way)
            performed++
            delay(SCROLL_SETTLE_MS)
            reader.findMatches(needle, 1).firstOrNull()?.let {
                return "Found \"$needle\" after $performed swipe${if (performed == 1) "" else "s"} $way; " +
                    "it is index ${it.index} in the latest dump."
            }
        }
        return "\"$needle\" did not appear after $performed swipes $way; it may sit under different words or on another screen."
    }

    /** Scrolls one chosen element, which is how sideways toolbars and tab strips are navigated. */
    suspend fun scrollIn(index: Int, direction: String): String {
        val way = direction.trim().lowercase()
        if (way !in SWIPE_DIRECTIONS) return "Unknown direction \"$direction\"; use up, down, left or right."
        val node = reader.locate(index)
            ?: return "Nothing at index $index any more; the screen changed."
        if (!runCatching { node.isScrollable }.getOrDefault(false)) {
            return "The element at index $index (${describe(node)}) is not scrollable; " +
                "use scroll_to_text, or scroll_in on a container marked scrollable in the dump."
        }
        val action = if (way == "down" || way == "right") {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        if (runCatching { node.performAction(action) }.getOrDefault(false)) {
            return "Scrolled ${describe(node)} $way."
        }
        if (swipeInside(node, way)) return "Swiped inside ${describe(node)} $way."
        return "The element at index $index (${describe(node)}) refused to scroll $way."
    }

    /** Long-presses an element by index or by text; useful for context menus and reply popups. */
    suspend fun longPress(index: Int? = null, query: String? = null): String {
        val node = when {
            index != null -> reader.locate(index)
                ?: return "Nothing at index $index any more; the screen changed."

            !query.isNullOrBlank() -> reader.findByText(query)
                ?: return "No visible element contains \"$query\"."

            else -> return "Give long_press either an index or a text to look for."
        }
        val target = reader.clickableAncestor(node) ?: node
        if (runCatching { target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) }.getOrDefault(false)) {
            return "Long-pressed ${describe(target)}."
        }
        val (x, y) = reader.centerOf(target)
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        if (service.gesture(path, LONG_PRESS_MS)) return "Long-pressed ${describe(target)} at ($x, $y)."
        return "Could not long-press ${describe(target)}."
    }

    /**
     * Submits the focused field with the IME enter action; when no field is focused it taps the
     * first visible send, generate, submit or search control instead.
     */
    suspend fun pressEnter(): String {
        val field = reader.focusedEditable()
        if (field != null) {
            val action = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
            if (runCatching { field.performAction(action) }.getOrDefault(false)) {
                return "Pressed enter in ${describe(field)}."
            }
        }
        val submit = reader.findByLabel(predicate = { label -> isSubmitLabel(label) })
        if (submit != null) return tapNode(submit)
        return "No focused text field and no send, generate or submit control is visible; tap the submit button yourself."
    }

    /** Polls the screen until [query] shows up, so a generated or loaded result is not tapped blind. */
    suspend fun waitForText(query: String, timeoutMs: Int = 20_000): String {
        val needle = query.trim()
        if (needle.isEmpty()) return "Empty text."
        val budget = timeoutMs.coerceIn(1_000, 60_000)
        val deadline = System.currentTimeMillis() + budget
        while (true) {
            reader.findMatches(needle, 1).firstOrNull()?.let {
                return "\"$needle\" is on screen at index ${it.index}."
            }
            if (System.currentTimeMillis() >= deadline) break
            delay(WAIT_POLL_MS)
        }
        return "\"$needle\" did not appear within ${budget / 1000}s."
    }

    /**
     * Types [text], escalating through the ways real fields accept input: set text, tap the field
     * first, paste, then grow the text in runs. The field is re-read afterwards and the answer says
     * honestly how much of the text landed.
     */
    suspend fun setText(index: Int?, text: String): String {
        val node = (if (index != null) reader.locate(index) else null)
            ?: reader.focusedEditable()
            ?: return "No text field is focused and none was given."
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }

        if (performSetText(node, text)) {
            verifiedTyping(node, text, "directly")?.let { return it }
        }
        if (text.isEmpty()) return reportShortfall(node, text)

        // Compose fields and WebView editors often start listening only after a real touch.
        val (x, y) = reader.centerOf(node)
        if (service.tapPoint(x, y)) {
            runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
            if (performSetText(node, text)) {
                verifiedTyping(node, text, "after tapping the field")?.let { return it }
            }
        }

        if (pasteInto(node, text)) {
            verifiedTyping(node, text, "by pasting")?.let { return it }
        }

        typeInGrowingRuns(node, text)
        return verifiedTyping(node, text, "in growing runs") ?: reportShortfall(node, text)
    }

    suspend fun clearText(index: Int): String = setText(index, "")

    suspend fun scroll(direction: String): String {
        val way = direction.trim().lowercase()
        if (way !in SWIPE_DIRECTIONS) return "Unknown direction \"$direction\"; use up, down, left or right."
        val scrollable = reader.scrollableNode()
        if (scrollable != null && (way == "down" || way == "up")) {
            val action = if (way == "down") {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            if (runCatching { scrollable.performAction(action) }.getOrDefault(false)) {
                return "Scrolled $way."
            }
        }
        return if (swipeScreen(way)) "Swiped $way." else "Swipe $way failed."
    }

    fun globalAction(action: String): String {
        val constant = when (action.trim().lowercase()) {
            "back" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
            "recents", "recent_apps" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications", "notification_shade" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "lock", "lock_screen" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            "power_dialog" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            else -> return "Unknown navigation action \"$action\"."
        }
        return if (service.global(constant)) "Pressed $action." else "The system refused the $action action."
    }

    fun listApps(): String {
        val apps = launchableApps()
        if (apps.isEmpty()) return "No launchable apps were found."
        return apps.joinToString(", ") { "${it.label} (${it.packageName})" }.take(4000)
    }

    fun openApp(query: String): String {
        val apps = launchableApps()
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return "Empty app name."
        val exact = apps.firstOrNull { it.label.lowercase() == needle || it.packageName.lowercase() == needle }
        val matches = apps.filter { it.label.lowercase().contains(needle) || it.packageName.lowercase().contains(needle) }
        val chosen = exact ?: matches.firstOrNull()
            ?: return "No installed app matches \"$query\". Candidates: " +
                apps.take(40).joinToString(", ") { it.label }
        val intent = runCatching { service.packageManager.getLaunchIntentForPackage(chosen.packageName) }.getOrNull()
            ?: return "${chosen.label} has no launchable activity."
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (start(intent)) "Opened ${chosen.label}." else "Could not open ${chosen.label}."
    }

    fun openUrl(url: String): String {
        val normalized = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalized)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (start(intent)) "Opened $normalized." else "No app can open $normalized."
    }

    fun dial(number: String): String {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (start(intent)) "Dialled $number; the user still has to press call." else "Could not open the dialler."
    }

    fun sendSms(number: String, message: String): String {
        val granted = directSms && ContextCompat.checkSelfPermission(service, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            val sent = runCatching {
                val manager = smsManager()
                val parts = manager.divideMessage(message)
                if (parts.size <= 1) {
                    manager.sendTextMessage(number, null, message, null, null)
                } else {
                    manager.sendMultipartTextMessage(number, null, parts, null, null)
                }
            }
            if (sent.isSuccess) return "Sent the SMS to $number."
        }
        val composer = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}"))
            .putExtra("sms_body", message)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (start(composer)) {
            "The message is pre-filled in the messaging app on $number; the user has to press send (direct sending is off in Settings)."
        } else {
            "Could not send or compose an SMS to $number."
        }
    }

    fun setAlarm(hour: Int, minute: Int, label: String): String {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val at = "%02d:%02d".format(hour, minute)
        return if (start(intent)) "Alarm set for $at." else "No clock app accepted the alarm for $at."
    }

    fun openSystemSettings(section: String): String {
        val action = when (section.trim().lowercase()) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "apps", "applications" -> Settings.ACTION_APPLICATION_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (start(intent)) "Opened the system settings screen ($section)." else "Could not open system settings."
    }

    fun findContacts(query: String): String {
        val granted = ContextCompat.checkSelfPermission(service, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return "The contacts permission is not granted, so I cannot look anyone up."
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val found = ArrayList<String>()
        runCatching {
            service.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                arrayOf("%$query%"),
                null,
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(projection[0])
                val numberIndex = cursor.getColumnIndex(projection[1])
                while (cursor.moveToNext() && found.size < 10) {
                    val name = if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else ""
                    val number = if (numberIndex >= 0) cursor.getString(numberIndex).orEmpty() else ""
                    if (name.isNotBlank()) found.add("$name: $number")
                }
            }
        }
        return if (found.isEmpty()) "No contact matches \"$query\"." else found.joinToString("\n")
    }

    /** The app currently on screen, so its built-in manual can be looked up. */
    fun foregroundApp(): AppEntry {
        val (label, packageName) = reader.foregroundApp()
        return AppEntry(label = label, packageName = packageName)
    }

    fun launchableApps(): List<AppEntry> = appsCache.get() ?: buildApps().also { appsCache.set(it) }

    private fun buildApps(): List<AppEntry> {
        val manager = service.packageManager
        val probe = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved: List<ResolveInfo> = runCatching { manager.queryIntentActivities(probe, 0) }
            .getOrDefault(emptyList())
        return resolved.mapNotNull { info ->
            val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
            val label = runCatching { info.loadLabel(manager).toString() }.getOrDefault(packageName)
            AppEntry(label = label, packageName = packageName)
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }

    /** Scrolls the container that should hold what we are looking for, else the screen itself. */
    private suspend fun scrollContainerOrScreen(way: String) {
        val container = preferredScrollContainer(way)
        if (container != null) {
            val action = if (way == "down" || way == "right") {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            if (runCatching { container.performAction(action) }.getOrDefault(false)) return
            if (swipeInside(container, way)) return
        }
        swipeScreen(way)
    }

    /**
     * The scrollable node whose bounds cross the centre of the screen along the swipe axis, which
     * is the strip or list the finger would actually touch, else the first scrollable one.
     */
    private fun preferredScrollContainer(way: String): AccessibilityNodeInfo? {
        val nodes = reader.scrollableNodes()
        if (nodes.isEmpty()) return null
        val (width, height) = service.screenSize()
        val horizontal = way == "left" || way == "right"
        return nodes.firstOrNull { node ->
            val bounds = reader.boundsOf(node)
            if (horizontal) {
                bounds.left <= width / 2 && width / 2 <= bounds.right
            } else {
                bounds.top <= height / 2 && height / 2 <= bounds.bottom
            }
        } ?: nodes.first()
    }

    private suspend fun swipeScreen(way: String): Boolean {
        val (width, height) = service.screenSize()
        val (from, to) = when (way) {
            "up" -> (height * 0.72f).toInt() to (height * 0.28f).toInt()
            "down" -> (height * 0.28f).toInt() to (height * 0.72f).toInt()
            "left" -> (width * 0.8f).toInt() to (width * 0.2f).toInt()
            "right" -> (width * 0.2f).toInt() to (width * 0.8f).toInt()
            else -> return false
        }
        return if (way == "up" || way == "down") {
            service.swipe(width / 2, from, width / 2, to)
        } else {
            service.swipe(from, height / 2, to, height / 2)
        }
    }

    /** Swipes inside a node's own bounds, the gesture equivalent of scrolling that strip. */
    private suspend fun swipeInside(node: AccessibilityNodeInfo, way: String): Boolean {
        val bounds = reader.boundsOf(node)
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val verticalStep = (bounds.height() * 0.25f).toInt().coerceAtLeast(1)
        val horizontalStep = (bounds.width() * 0.25f).toInt().coerceAtLeast(1)
        return when (way) {
            "down" -> service.swipe(cx, bounds.top + verticalStep, cx, bounds.bottom - verticalStep)
            "up" -> service.swipe(cx, bounds.bottom - verticalStep, cx, bounds.top + verticalStep)
            "left" -> service.swipe(bounds.right - horizontalStep, cy, bounds.left + horizontalStep, cy)
            "right" -> service.swipe(bounds.left + horizontalStep, cy, bounds.right - horizontalStep, cy)
            else -> false
        }
    }

    private fun performSetText(node: AccessibilityNodeInfo, text: String): Boolean {
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments) }.getOrDefault(false)
    }

    private fun pasteInto(node: AccessibilityNodeInfo, text: String): Boolean {
        val clipboard = service.getSystemService(ClipboardManager::class.java) ?: return false
        val copied = runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("humanphone", text)) }
        if (copied.isFailure) return false
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
        return runCatching { node.performAction(AccessibilityNodeInfo.ACTION_PASTE) }.getOrDefault(false)
    }

    /**
     * Offers the text in growing runs, one character, then two, then four, which is what unlocks
     * Compose text fields and WebView editors that refuse a single bulk set.
     */
    private fun typeInGrowingRuns(node: AccessibilityNodeInfo, text: String) {
        var confirmed = 0
        var run = 1
        var guard = 0
        while (confirmed < text.length && guard < 24) {
            guard++
            val target = minOf(text.length, confirmed + run)
            performSetText(node, text.take(target))
            val observed = nodeText(node).length
            if (observed >= target) {
                confirmed = target
                run = minOf(text.length, run * 2)
            } else {
                if (observed <= confirmed) break
                confirmed = observed
                run = 1
            }
        }
    }

    /** The node's text as the accessibility layer currently reports it, after a re-read. */
    private fun nodeText(node: AccessibilityNodeInfo): String {
        runCatching { node.refresh() }
        return runCatching { node.text?.toString().orEmpty() }.getOrDefault("")
    }

    /** The success sentence, or null while the field does not hold the requested text yet. */
    private suspend fun verifiedTyping(node: AccessibilityNodeInfo, requested: String, how: String): String? {
        delay(FIELD_SETTLE_MS)
        if (nodeText(node) != requested) return null
        return if (requested.isEmpty()) {
            "The field ${describe(node)} is now empty."
        } else {
            "Typed ${requested.length} characters into ${describe(node)} $how."
        }
    }

    /** The honest answer when the field ended up holding something else than what was asked. */
    private suspend fun reportShortfall(node: AccessibilityNodeInfo, requested: String): String {
        delay(FIELD_SETTLE_MS)
        val observed = nodeText(node)
        if (observed.isEmpty()) return "Could not type into ${describe(node)}: the field stayed empty."
        val landed = observed.commonPrefixWith(requested).length
        if (landed == 0) return "Could not type into ${describe(node)}; it still reads \"${observed.take(100)}\"."
        return "Only $landed of ${requested.length} characters landed in ${describe(node)}; " +
            "it now reads \"${observed.take(100)}\"."
    }

    private fun isSubmitLabel(label: String): Boolean {
        val trimmed = label.trim().lowercase()
        if (trimmed.isEmpty()) return false
        if (trimmed in SUBMIT_LABELS) return true
        return trimmed.length <= 24 && SUBMIT_WORDS.any { trimmed.contains(it) }
    }

    private suspend fun tapNode(node: AccessibilityNodeInfo): String {
        val target = reader.clickableAncestor(node) ?: node
        if (runCatching { target.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)) {
            return "Tapped ${describe(target)}."
        }
        val (x, y) = reader.centerOf(target)
        return if (service.tapPoint(x, y)) {
            "Tapped ${describe(target)} at ($x, $y)."
        } else {
            "Could not tap ${describe(target)}."
        }
    }

    private fun describe(node: AccessibilityNodeInfo): String {
        val label = reader.labelOf(node)
        return if (label.isBlank()) "the ${classNameOf(node)}" else "\"${label.take(60)}\""
    }

    private fun classNameOf(node: AccessibilityNodeInfo): String =
        runCatching { node.className?.toString()?.substringAfterLast('.') }.getOrNull() ?: "View"

    private fun start(intent: Intent): Boolean = try {
        service.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            service.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
        } else {
            SmsManager.getDefault()
        }

    companion object {
        private const val FIELD_SETTLE_MS = 150L
        private const val WAIT_POLL_MS = 400L
        private const val SCROLL_SETTLE_MS = 450L
        private const val LONG_PRESS_MS = 700L
        private val SWIPE_DIRECTIONS = setOf("up", "down", "left", "right")
        private val SUBMIT_LABELS = setOf("go", "done", "ok", "send", "→", "↵")
        private val SUBMIT_WORDS = setOf("send", "generate", "submit", "search")
    }
}
