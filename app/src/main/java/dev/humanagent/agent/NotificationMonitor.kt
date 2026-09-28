package dev.humanagent.agent

import android.app.Notification
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import dev.humanagent.util.JsonArgs
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** One notification the assistant overheard while Auto mode is on. */
data class NotificationEvent(
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val whenMs: Long,
)

/**
 * In-process pipe from the accessibility service (which hears every notification) to the
 * agent service, which decides what Auto mode wants to do with each one.
 */
object NotificationBus {

    private val _events = MutableSharedFlow<NotificationEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<NotificationEvent> = _events.asSharedFlow()

    fun publish(event: NotificationEvent) {
        _events.tryEmit(event)
    }

    /** Flattens one posted notification into a compact event, or null when it has no text. */
    fun fromAccessibilityEvent(
        context: Context,
        event: AccessibilityEvent,
        ownPackage: String,
    ): NotificationEvent? {
        if (event.eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) return null
        val source = event.packageName?.toString().orEmpty()
        // Never react to the assistant's own notifications: that would feed itself in a loop.
        if (source == ownPackage || source.isBlank()) return null
        val notification = event.parcelableData as? Notification ?: return null
        val extras = notification.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            )?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return null
        return NotificationEvent(
            packageName = source,
            appName = appName(context, source),
            title = title,
            text = text,
            whenMs = event.eventTime,
        )
    }

    fun appName(context: Context, packageName: String): String =
        runCatching {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(packageName, 0),
            ).toString()
        }.getOrDefault(packageName)

    /** Human-facing one-liner describing a notification, used in prompts and logs. */
    fun describe(event: NotificationEvent): String = buildString {
        append(event.appName)
        if (event.title.isNotBlank()) append(" — ${event.title}")
        if (event.text.isNotBlank()) {
            append(": ")
            append(event.text.take(400))
        }
    }
}

/** What the model wants Auto mode to do with one notification. */
data class AutoDecision(
    val action: String, // "reply" | "skip"
    val replyText: String,
    val app: String,
    val note: String,
) {
    val isReply: Boolean get() = action == "reply" && replyText.isNotBlank()

    companion object {
        const val SKIP = "skip"

        /** Tolerant reader for the one-JSON-object verdict the model is asked to return. */
        fun parse(raw: String): AutoDecision {
            val action = (JsonArgs.string(raw, "action") ?: "skip").lowercase().trim()
            return AutoDecision(
                action = if (action.equals("reply", ignoreCase = true)) "reply" else "skip",
                replyText = JsonArgs.string(raw, "replyText").orEmpty(),
                app = JsonArgs.string(raw, "app").orEmpty(),
                note = JsonArgs.string(raw, "note").orEmpty(),
            )
        }
    }
}

/** The verdict prompt: strict skip-default, human-toned drafts, never touches OTP codes. */
object AutoModePrompts {
    const val SYSTEM: String = """
You are the auto-answering watchdog of HumanPhone, a personal assistant app. New notifications
arrive one by one and you decide for each: does a person actually need a reply?
Rules:
- Skip promotions, newsletters, news headlines, receipts, app announcements, silent warnings,
  system notices, battery/cloud/storage notices and everything a machine sent.
- NEVER reply to one-time codes, verification or OTP messages, and never repeat a code.
- Reply only when a human wrote to the user personally and clearly waits for an answer:
  a question, a request, an invitation, a message that ends a silence between friends or family.
- If a human message only informs ("on my way", "thanks") or the thread needs nothing, skip.
- replyText: draft the user's answer on their behalf, in the language the message was written in.
  One or two short sentences, warm and human, no emoji, no greetings clichés, no signature,
  never say you are an AI or an assistant.
- app: the messaging or mail app the reply belongs to, for example WhatsApp, Gmail, Telegram.
- note: one short sentence saying what was decided and why.
- When anything is unclear, choose skip. Missing context is never a reason to speak for the user.
Answer with a single JSON object and nothing else:
{"action":"reply","replyText":"…","app":"…","note":"…"}
or {"action":"skip","replyText":"","app":"…","note":"…"}
"""
}