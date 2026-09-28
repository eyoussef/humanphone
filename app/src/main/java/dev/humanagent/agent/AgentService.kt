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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.humanagent.HumanPhoneApp
import dev.humanagent.MainActivity
import dev.humanagent.R
import dev.humanagent.llm.LlmClient
import dev.humanagent.llm.Message
import dev.humanagent.llm.SettingsStore
import dev.humanagent.llm.StreamEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startInForeground("Ready when you are.")

        val app = HumanPhoneApp.instance
        val loop = AgentLoop(this, app.settingsStore, app.memory, app.speaker)
        engine = loop

        scope.launch {
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
            }
        }

        // Auto mode tracks the switch in settings; the bus feeds it notifications.
        scope.launch {
            app.settingsStore.settings.collect { settings -> _autoMode.value = settings.autoMode }
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
    private suspend fun handleNotificationEvent(settingsStore: SettingsStore, event: NotificationEvent) {
        val settings = settingsStore.current()
        if (!settings.autoMode) return
        if (_running.value) return // never interrupt a task the user started

        val now = System.currentTimeMillis()
        val fingerprint = "${event.packageName}|${event.title}|${event.text.take(120)}"
        synchronized(lastAutoHandled) {
            lastAutoHandled.entries.removeAll { it.value < now - AUTO_COOLDOWN_MS }
            if (lastAutoHandled.containsKey(fingerprint)) return
        }
        val decision = runCatching { classify(event) }.getOrNull() ?: return
        synchronized(lastAutoHandled) {
            lastAutoHandled[fingerprint] = System.currentTimeMillis()
            // A reply puts the app on cooldown: our own outgoing echo must not loop us.
            if (decision.isReply) lastAutoHandled["app:" + event.packageName] = System.currentTimeMillis()
        }
        if (!decision.isReply) return

        notifyStatus("Auto mode · ${decision.app.ifBlank { event.appName }}: ${decision.note}")
        engine?.run(autoCommand(event, decision))
    }

    /** One cheap model call: does this notification deserve a reply, and what should it say? */
    private suspend fun classify(event: NotificationEvent): AutoDecision {
        val settings = HumanPhoneApp.instance.settingsStore.current()
        val config = settings.toProviderConfig()
        if (!config.isUsable) return AutoDecision(AutoDecision.SKIP, "", "", "No model configured.")
        val prompt = buildString {
            appendLine("New notification:")
            appendLine(NotificationBus.describe(event))
            appendLine()
            appendLine("Decide now and answer with the JSON object only.")
        }
        val answer = StringBuilder()
        var finalText: String? = null
        LlmClient(config).stream(
            listOf(Message.system(AutoModePrompts.SYSTEM), Message.user(prompt)),
        ).collect { streamEvent ->
            when (streamEvent) {
                is StreamEvent.TextDelta -> answer.append(streamEvent.text)
                is StreamEvent.Completed -> finalText = streamEvent.message.content
                is StreamEvent.Failure -> Unit
            }
        }
        val raw = finalText?.takeIf { it.isNotBlank() } ?: answer.toString()
        return AutoDecision.parse(raw)
    }

    /** The instruction the agent loop gets when Auto mode decided to answer. */
    private fun autoCommand(event: NotificationEvent, decision: AutoDecision): String =
        buildString {
            append("Auto task: a notification arrived in ${event.appName}")
            if (event.title.isNotBlank()) append(" — ${event.title}")
            append(". Send this reply on my behalf: \"${decision.replyText}\". ")
            append("Open ${decision.app.ifBlank { event.appName }}, find the conversation, send the message, then finish. ")
            append("If you cannot reach the conversation within a few steps, finish and say why.")
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
        const val ACTION_RUN = "dev.humanagent.action.RUN"
        const val ACTION_HALT = "dev.humanagent.action.HALT"
        const val EXTRA_COMMAND = "command"

        private const val CHANNEL_ID = "humanphone_agent"
        private const val NOTIFICATION_ID = 4711

        /** One auto decision per app in this window: our own replies must not loop back to us. */
        private const val AUTO_COOLDOWN_MS = 120_000L

        private val _autoMode = MutableStateFlow(false)
        val autoMode: StateFlow<Boolean> = _autoMode.asStateFlow()

        private val _loop = MutableStateFlow(AgentRunState())
        val loop: StateFlow<AgentRunState> = _loop.asStateFlow()

        private val _running = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _running.asStateFlow()

        @Volatile
        private var instance: AgentService? = null

        /** Starts the foreground service (the user-initiated on-device agent loop). */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, AgentService::class.java))
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
