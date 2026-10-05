package dev.humanagent.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.humanagent.BuildConfig
import dev.humanagent.HumanPhoneApp
import dev.humanagent.MainActivity
import dev.humanagent.R
import dev.humanagent.llm.LlmClient
import dev.humanagent.llm.Message
import dev.humanagent.llm.SettingsStore
import dev.humanagent.llm.StreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Keeps the assistant alive: the live agent loop and the notification that lets
 * the user halt whatever the phone is doing.
 */
class AgentService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var engine: AgentLoop? = null

    /** Decisions already taken, to skip duplicate notifications and reply loops. */
    private val lastAutoHandled = mutableMapOf<String, Long>()

    /** Notifications heard while a run was active, replayed when the phone goes idle. */
    private val pending = PendingNotifications()

    /** The hourly ceiling on classify calls and agent runs. */
    private val governor = AutoModeGovernor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startInForeground("Ready when you are.")

        val app = HumanPhoneApp.instance
        val loop = AgentLoop(this, app.settingsStore, app.memory, app.ledger, app.speaker)
        engine = loop

        scope.launch {
            var wasRunning = false
            loop.state.collect { state ->
                _loop.value = state
                _running.value = state.running
                notifyStatus(
                    when {
                        state.error != null -> state.error
                        state.running -> state.liveText.ifBlank { getString(R.string.notification_working) }
                        else -> state.lastReply.ifBlank { getString(R.string.notification_ready) }
                    }
                )
                if (wasRunning && !state.running) {
                    replayPending(app.settingsStore)
                    deliverIfOwed(app)
                }
                wasRunning = state.running
            }
        }

        // Auto mode tracks the switch in settings; the bus feeds it notifications.
        scope.launch {
            app.settingsStore.settings.collect { settings ->
                _autoMode.value = settings.autoMode
                if (!settings.autoMode) synchronized(pending) { pending.clear() }
            }
        }
        scope.launch {
            NotificationBus.events.collect { event -> handleNotificationEvent(app.settingsStore, event) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HALT -> engine?.halt()
            ACTION_RUN -> intent.getStringExtra(EXTRA_COMMAND)?.let { command -> engine?.run(command) }
            else -> Unit
        }
        return START_STICKY
    }

    override fun onDestroy() {
        engine?.shutdown()
        scope.cancel()
        instance = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /** Classifies one notification and, when a reply is warranted, hands it to the agent. */
    private suspend fun handleNotificationEvent(settingsStore: SettingsStore, event: NotificationEvent): Boolean {
        val settings = settingsStore.current()
        if (BuildConfig.DEBUG) {
            Log.i(AUTO_TAG, "Heard ${event.packageName}: ${event.title.take(80)} (auto mode ${settings.autoMode})")
        }
        if (!settings.autoMode) return false
        // The ceiling keeps one noisy or malicious app from turning the user's provider quota
        // and battery into somebody else's notification traffic.
        if (!governor.mayClassify()) {
            notifyStatus("Auto mode is quiet for now — too many messages this hour.")
            return false
        }
        if (_running.value) {
            // Never interrupt a task in progress: queue the notification, replay it when idle.
            synchronized(pending) { pending.offer(event) }
            if (BuildConfig.DEBUG) Log.i(AUTO_TAG, "Busy — queued (${pending.size}): ${event.title.take(60)}")
            return false
        }

        val now = System.currentTimeMillis()
        val fingerprint = "${event.packageName}|${event.title}|${event.text.take(120)}"
        synchronized(lastAutoHandled) {
            // The fingerprint window prunes even the echo entries, which live the longest now.
            lastAutoHandled.entries.removeAll { it.value < now - AUTO_FINGERPRINT_COOLDOWN_MS }
            if (lastAutoHandled.containsKey(fingerprint)) return false
            // A reply a moment ago briefly puts the whole app on cooldown, so the assistant's
            // own outgoing echo cannot answer itself. Fresh messages from the same app clear
            // again quickly, so a real back-and-forth keeps going.
            lastAutoHandled["app:" + event.packageName]?.let { echo ->
                if (now - echo < AUTO_ECHO_COOLDOWN_MS) return false
            }
        }
        val decision = runCatching { classify(event) }.getOrNull()
        if (decision == null) {
            Log.i(AUTO_TAG, "Could not reach the model — notification left unanswered.")
            notifyStatus("Auto mode could not reach the model — nothing answered.")
            return false
        }
        synchronized(lastAutoHandled) {
            lastAutoHandled[fingerprint] = System.currentTimeMillis()
            // A reply puts the app on cooldown: our own outgoing echo must not loop us.
            if (decision.actionable) lastAutoHandled["app:" + event.packageName] = System.currentTimeMillis()
        }
        if (!decision.actionable) {
            if (BuildConfig.DEBUG) Log.i(AUTO_TAG, "Skip ${event.packageName} (skip)")
            notifyStatus("Auto mode · skipped: a message that needs no answer.")
            return false
        }
        if (!governor.mayRun()) {
            notifyStatus("Auto mode is resting — it acted on enough messages this hour.")
            return false
        }

        Log.i(AUTO_TAG, "Replying to ${event.packageName} — run started")
        notifyStatus("Auto mode · working on a message in ${decision.app.ifBlank { event.appName }}.")
        // From the moment a task is accepted, the result is a tracked obligation: the loop is
        // told which conversation it must land in, and the ledger keeps it open until
        // confirm_delivered closes it — trimming or a dead process cannot make it vanish.
        val targetApp = decision.app.ifBlank { event.appName }
        val destination = if (event.title.isNotBlank()) {
            "the conversation \"$event.title\" in $targetApp"
        } else {
            "the current conversation in $targetApp"
        }
        val obligation = if (decision.task.isNotBlank()) {
            HumanPhoneApp.instance.ledger.open(decision.task, destination, targetApp)
        } else {
            null
        }
        engine?.run(autoCommand(event, decision, obligation), obligation)
        return true
    }

    /**
     * Replays the notifications that arrived while a run was active. Skip decisions let the loop
     * continue; a started run hands the rest over to the next run-end replay.
     */
    private suspend fun replayPending(settingsStore: SettingsStore) {
        while (!_running.value) {
            if (!_autoMode.value) {
                synchronized(pending) { pending.clear() }
                return
            }
            val event = synchronized(pending) { pending.drainOne() } ?: return
            if (handleNotificationEvent(settingsStore, event)) return
        }
    }

    /**
     * One cheap model call: does this notification deserve a reply, and what should it say?
     * Returns null when the model could not be reached — the caller says so instead of
     * silently swallowing the notification.
     */
    private suspend fun classify(event: NotificationEvent): AutoDecision? {
        val settings = HumanPhoneApp.instance.settingsStore.current()
        val config = settings.toProviderConfig()
        if (!config.isUsable) {
            Log.i(AUTO_TAG, "No model configured — cannot classify anything.")
            return null
        }
        val prompt = buildString {
            appendLine("New notification:")
            appendLine(NotificationBus.describe(event))
            appendLine()
            appendLine("Decide now and answer with the JSON object only.")
        }
        var failure: String? = null
        repeat(2) { attempt ->
            val answer = StringBuilder()
            var finalText: String? = null
            try {
                LlmClient(config).stream(
                    listOf(
                        Message.system(
                            AutoModePrompts.system(settings.autoModePersona, HumanPhoneApp.instance.memory.renderPeople()),
                        ),
                        Message.user(prompt),
                    ),
                ).collect { streamEvent ->
                    when (streamEvent) {
                        is StreamEvent.TextDelta -> answer.append(streamEvent.text)
                        is StreamEvent.Completed -> finalText = streamEvent.message.content
                        is StreamEvent.Failure -> failure = streamEvent.message
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e.message ?: e.javaClass.simpleName
            }
            val raw = (finalText ?: answer.toString()).trim()
            if (raw.isNotBlank()) {
                if (BuildConfig.DEBUG) Log.i(AUTO_TAG, "Verdict: ${raw.take(300)}")
                val decision = AutoDecision.parse(raw)
                if (decision.actionable) return decision
                // A blank or fence-broken verdict the reader could not lift an action out of.
                if (decision.note.isBlank() && decision.replyText.isBlank()) {
                    failure = "Unreadable verdict: ${raw.take(160)}"
                } else {
                    return decision
                }
            }
            failure = failure ?: "The model stopped responding."
            if (attempt == 0) delay(1_500)
        }
        Log.i(AUTO_TAG, "No verdict: $failure")
        return null
    }

    /** The instruction the agent loop gets when Auto mode decided to act. */
    private fun autoCommand(event: NotificationEvent, decision: AutoDecision, obligation: Obligation?): String =
        buildString {
            append("Auto task: a notification arrived in ${event.appName}")
            if (event.title.isNotBlank()) append(" — ${event.title}")
            appendLine(".")
            appendLine("1. Reach the conversation it arrived in.")
            if (decision.replyText.isNotBlank()) {
                appendLine("2. Send this short reply now, exactly as written: \"${decision.replyText}\"")
            }
            if (decision.task.isNotBlank()) {
                appendLine(
                    "3. Then actually do what was asked, with the tools you have (apps, browser, whatever it takes): ${decision.task}",
                )
                appendLine("4. Send the result as another message in the SAME conversation you acknowledged.")
                appendLine(
                    "5. Call confirm_delivered with the exact text of that result message — the task is not done until it is sent and confirmed; finish before that gets it refused.",
                )
                appendLine("6. finish.")
                appendLine("Do not repeat the acknowledgment while working; if the screen changed, read it before typing.")
            } else {
                appendLine("That immediate reply was the whole answer; nothing more to do — finish.")
            }
            appendLine("If you cannot reach the conversation within a few steps, finish and say why.")
            appendLine("The finish ends everything: the phone returns home to HumanPhone on its own, so the next notification is heard again.")
        }

    /**
     * A result the watchdog still owes, from a run that ended without confirming delivery.
     * Runs as its own small task so the promised answer is not lost when one run fails to
     * get it through.
     */
    private suspend fun deliverIfOwed(app: HumanPhoneApp) {
        if (!_autoMode.value || _running.value) return
        val obligation = app.ledger.current()?.takeIf { it.open } ?: return
        if (BuildConfig.DEBUG) Log.i(AUTO_TAG, "Owed result to ${obligation.destination} — delivery run started")
        notifyStatus("Auto mode · sending the promised result to ${obligation.destination}…")
        engine?.run(deliveryCommand(obligation), obligation)
    }

    private fun deliveryCommand(obligation: Obligation): String = buildString {
        appendLine("Owed result: the earlier task \"${obligation.task}\" finished, but its answer never reached the conversation.")
        appendLine("Deliver this result, word for word, as a chat message to ${obligation.destination}: \"${obligation.result}\"")
        appendLine("1. Open the app and reach that conversation (search for the name in the chat list if needed).")
        appendLine("2. Type the result into the message field and send it.")
        appendLine("3. Call confirm_delivered with the exact text, then finish.")
        appendLine("If the conversation truly cannot be reached, finish and say so plainly.")
    }

    private fun startInForeground(text: String) {
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(text), type)
    }

    private fun notifyStatus(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val halt = PendingIntent.getService(
            this,
            1,
            Intent(this, AgentService::class.java).setAction(ACTION_HALT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("HumanPhone")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notification_halt), halt)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_agent_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.channel_agent_description)
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val AUTO_TAG = "HumanPhoneAuto"

        const val ACTION_RUN = "dev.humanagent.action.RUN"
        const val ACTION_HALT = "dev.humanagent.action.HALT"
        const val EXTRA_COMMAND = "command"

        private const val CHANNEL_ID = "humanphone_agent"
        private const val NOTIFICATION_ID = 4711

        /**
         * The same package+title+text seen again in this window is one notification, not a new one.
         */
        private const val AUTO_FINGERPRINT_COOLDOWN_MS = 120_000L

        /** After a reply, a short same-app window blocks the assistant's own outgoing echo. */
        private const val AUTO_ECHO_COOLDOWN_MS = 45_000L

        private val _autoMode = MutableStateFlow(false)
        val autoMode: StateFlow<Boolean> = _autoMode.asStateFlow()

        private val _loop = MutableStateFlow(AgentRunState())
        val loop: StateFlow<AgentRunState> = _loop.asStateFlow()

        private val _running = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _running.asStateFlow()

        @Volatile
        internal var instance: AgentService? = null

        /** Starts the foreground service (the user-initiated on-device agent loop). */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, AgentService::class.java))
            }.onFailure { failure ->
                // Android 12+ refuses background foreground-service starts unless an exemption
                // applies (ignoring battery optimisation is the one Settings points at); a
                // failure here must not pass silently or Auto mode goes deaf without a trace.
                Log.w(AUTO_TAG, "Could not start the agent service: $failure")
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, AgentService::class.java)) }
        }

        /** Hands a task to the assistant, starting the service when it is not up yet. */
        fun run(context: Context, command: String) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, AgentService::class.java)
                        .setAction(ACTION_RUN)
                        .putExtra(EXTRA_COMMAND, command),
                )
            }
        }

        /** Stops the running task but keeps the service up. */
        fun halt(context: Context) {
            instance?.engine?.halt()
        }
    }
}

/**
 * Notifications that arrived while the agent was busy. Keeps the freshest few in arrival order and
 * replays exactly one per idle moment, so a busy task is not interrupted and nothing still pending
 * is lost between runs.
 */
class PendingNotifications {

    private val items = ArrayDeque<NotificationEvent>()

    /** New arrivals evict the oldest once the queue is full. */
    fun offer(event: NotificationEvent) {
        if (items.size >= PENDING_LIMIT) items.removeFirst()
        items.addLast(event)
    }

    fun drainOne(): NotificationEvent? = if (items.isEmpty()) null else items.removeFirst()

    fun clear() = items.clear()

    val size: Int get() = items.size

    companion object {
        const val PENDING_LIMIT = 8
    }
}
