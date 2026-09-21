package dev.humanagent.agent

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
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

data class AppEntry(val label: String, val packageName: String)

/**
 * Everything the assistant can physically do on the phone. Each method returns a short sentence
 * describing what happened, which is fed straight back to the model as the tool result.
 */
class UiActionExecutor(
    private val service: AgentAccessibilityService,
    private val reader: ScreenReader,
) {

    private val appsCache = AtomicReference<List<AppEntry>?>(null)

    suspend fun tapIndex(index: Int): String {
        val node = reader.locate(index)
            ?: return "Nothing at index $index any more; the screen changed."
        return tapNode(node)
    }

    suspend fun tapText(query: String): String {
        val node = reader.findByText(query)
            ?: return "No visible element contains \"$query\"."
        return tapNode(node)
    }

    suspend fun tapDescription(query: String): String {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return "Empty description."
        val node = reader.findByText(needle)
            ?: return "No element with description \"$query\"."
        return tapNode(node)
    }

    suspend fun setText(index: Int?, text: String): String {
        val node = (if (index != null) reader.locate(index) else null)
            ?: reader.focusedEditable()
            ?: return "No text field is focused and none was given."
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments) }.getOrDefault(false)) {
            return "Typed ${text.length} characters into ${describe(node)}."
        }
        val clipboard = service.getSystemService(ClipboardManager::class.java)
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("humanphone", text))
            if (runCatching { node.performAction(AccessibilityNodeInfo.ACTION_PASTE) }.getOrDefault(false)) {
                return "Typed the text into ${describe(node)} by pasting."
            }
        }
        val (x, y) = reader.centerOf(node)
        if (service.tapPoint(x, y)) {
            return "The field ${describe(node)} does not accept direct text; tapped it at ($x, $y) so the keyboard opens."
        }
        return "Could not type into ${describe(node)}."
    }

    suspend fun clearText(index: Int): String = setText(index, "")

    suspend fun scroll(direction: String): String {
        val way = direction.trim().lowercase()
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
        val (width, height) = service.screenSize()
        val (from, to) = when (way) {
            "up" -> (height * 0.72f).toInt() to (height * 0.28f).toInt()
            "down" -> (height * 0.28f).toInt() to (height * 0.72f).toInt()
            "left" -> (width * 0.8f).toInt() to (width * 0.2f).toInt()
            "right" -> (width * 0.2f).toInt() to (width * 0.8f).toInt()
            else -> return "Unknown direction \"$direction\"; use up, down, left or right."
        }
        val ok = if (way == "up" || way == "down") {
            service.swipe(width / 2, from, width / 2, to)
        } else {
            service.swipe(from, height / 2, to, height / 2)
        }
        return if (ok) "Swiped $way." else "Swipe $way failed."
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
        val granted = ContextCompat.checkSelfPermission(service, Manifest.permission.SEND_SMS) ==
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
            "The SMS permission is not granted, so the message is pre-filled in the messaging app and the user has to press send."
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
        val text = runCatching { node.text?.toString().orEmpty() }.getOrDefault("")
        val description = runCatching { node.contentDescription?.toString().orEmpty() }.getOrDefault("")
        val className = runCatching { node.className?.toString()?.substringAfterLast('.') }.getOrNull() ?: "element"
        val label = text.ifBlank { description }
        return if (label.isBlank()) "the $className" else "\"${label.take(60)}\""
    }

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
}
