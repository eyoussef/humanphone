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
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.humanagent.HumanPhoneApp
import dev.humanagent.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps the assistant alive: the floating dot, the live agent loop, and the notification that lets
 * the user halt whatever the phone is doing.
 */
class AgentService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var engine: AgentLoop? = null
    private var bubble: OverlayBubble? = null
    private var listenJob: Job? = null

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
                bubble?.setLabel(if (state.running) "•••" else "HP")
                notifyStatus(
                    when {
                        state.error != null -> state.error
                        state.running -> state.liveText.ifBlank { "Working…" }
                        else -> state.lastReply.ifBlank { "Ready when you are." }
                    }
                )
            }
        }

        if (Settings.canDrawOverlays(this)) installBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HALT -> engine?.halt()
            ACTION_RUN -> {
                installBubble()
                intent.getStringExtra(EXTRA_COMMAND)?.let { command -> engine?.run(command) }
            }
            else -> installBubble()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        listenJob?.cancel()
        engine?.shutdown()
        bubble?.hide()
        scope.cancel()
        instance = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun installBubble() {
        if (bubble?.isShowing == true) return
        bubble = OverlayBubble(
            context = this,
            onTap = { openApp() },
            onLongPress = { listenForCommand() },
        ).also { it.show() }
    }

    private fun openApp() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    private fun listenForCommand() {
        val app = HumanPhoneApp.instance
        listenJob?.cancel()
        bubble?.setLabel("🎙")
        app.voice.startListening()
        listenJob = scope.launch {
            val heard = withTimeoutOrNull(LISTEN_TIMEOUT_MS) { app.voice.heard.first() }
            app.voice.stopListening()
            bubble?.setLabel("HP")
            if (!heard.isNullOrBlank()) engine?.run(heard)
        }
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
            .addAction(0, "Halt", halt)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Assistant",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "HumanPhone task status and the floating dot."
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
        private const val LISTEN_TIMEOUT_MS = 15_000L

        private val _loop = MutableStateFlow(AgentRunState())
        val loop: StateFlow<AgentRunState> = _loop.asStateFlow()

        private val _running = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _running.asStateFlow()

        @Volatile
        private var instance: AgentService? = null

        /** Starts the foreground service (and the floating dot when the user allowed overlays). */
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

        /** Stops the running task but keeps the service and the dot. */
        fun halt(context: Context) {
            instance?.engine?.halt()
        }
    }
}
