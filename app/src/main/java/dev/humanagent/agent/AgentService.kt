package dev.humanagent.agent

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.humanagent.HumanPhoneApp
import dev.humanagent.MainActivity
import dev.humanagent.chat.ChatEngine
import dev.humanagent.util.Markdown
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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

    /** Set when the dot was closed during a task, so the service stops as soon as the task ends. */
    private var stopWhenIdle = false

    /** Last status line shown in the notification, reused when the foreground types are re-claimed. */
    private var lastStatus = "Ready when you are."

    /** Types the running foreground notification was declared with, so they are only re-claimed once. */
    private var claimedTypes = 0

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
                    Markdown.singleLine(
                        when {
                            state.error != null -> state.error
                            state.running -> state.liveText.ifBlank { "Working…" }
                            else -> state.lastReply.ifBlank { "Ready when you are." }
                        }
                    )
                )
                // The dot was closed while a task was still running: now that it is done there is
                // nothing left for the service to keep alive.
                if (stopWhenIdle && !state.running) {
                    stopWhenIdle = false
                    stopSelf()
                }
            }
        }

        scope.launch {
            app.settingsStore.settings.collect { settings ->
                if (settings.showBubble && Settings.canDrawOverlays(this@AgentService)) {
                    // The dot is wanted again, so a dismissal during an earlier task no longer ends
                    // the service when that task finishes.
                    stopWhenIdle = false
                    installBubble()
                } else {
                    bubble?.hide()
                    bubble = null
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HALT -> engine?.halt()
            ACTION_RUN -> intent.getStringExtra(EXTRA_COMMAND)?.let { command -> engine?.run(command) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        listenJob?.cancel()
        engine?.shutdown()
        bubble?.hide()
        // Whatever happens next, the dot must not report a service that is no longer there.
        _running.value = false
        _loop.update { it.copy(running = false, liveText = "") }
        // A dictation the dot owned is over, so live mode gets the microphone back.
        runCatching { HumanPhoneApp.instance.chatEngine.resumeLiveListening(ChatEngine.LivePause.BUBBLE) }
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
            onClose = { dismissBubble() },
        ).also { it.show() }
    }

    /** The user closed the dot: take it away, remember the choice and let the service go too. */
    private fun dismissBubble() {
        bubble?.hide()
        bubble = null
        scope.launch { HumanPhoneApp.instance.settingsStore.update { it.copy(showBubble = false) } }
        // The dot is the service's main reason to run. A task that is still working keeps it alive;
        // otherwise the foreground notification goes away with the dot.
        if (engine?.state?.value?.running == true) {
            stopWhenIdle = true
            notifyStatus("Dot hidden — the running task continues. Turn the dot back on in Settings.")
        } else {
            stopSelf()
        }
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
        // A live conversation hands the microphone over while the dot dictates.
        app.chatEngine.pauseLiveListening(ChatEngine.LivePause.BUBBLE)
        app.voice.startListening()
        listenJob = scope.launch {
            // A missing permission or a device without a recogniser must not end in silence.
            val blocked = app.voice.notice.value
            if (blocked.isNotBlank()) {
                bubble?.setLabel("HP")
                notifyStatus(blocked)
                app.chatEngine.resumeLiveListening(ChatEngine.LivePause.BUBBLE)
                return@launch
            }
            val heard = withTimeoutOrNull(LISTEN_TIMEOUT_MS) { app.voice.heard.first() }
            app.voice.stopListening()
            bubble?.setLabel("HP")
            if (!heard.isNullOrBlank()) engine?.run(heard)
            app.chatEngine.resumeLiveListening(ChatEngine.LivePause.BUBBLE)
        }
    }

    private fun startInForeground(text: String) {
        createChannel()
        lastStatus = text
        val notification = buildNotification(text)
        val types = foregroundTypes()
        val started = runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
        }.isSuccess
        if (started) {
            claimedTypes = types
            return
        }
        // The microphone type is refused when the service is (re)started from the background; the
        // status notification still has to appear, so only the plain type is retried.
        val fallback = specialUseType()
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fallback)
        }
        claimedTypes = fallback
    }

    /** Re-claims the foreground types when the microphone permission appeared since service start. */
    private fun refreshForegroundTypes() {
        if (foregroundTypes() == claimedTypes) return
        startInForeground(lastStatus)
    }

    /**
     * Foreground-service types for this service: special use always, and the microphone as well
     * once the user allowed it, which is what Android asks for before a background service may
     * hand audio to the recogniser on the dot's long-press.
     */
    private fun foregroundTypes(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return specialUseType()
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return types
    }

    private fun specialUseType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }

    private fun notifyStatus(text: String) {
        lastStatus = text
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

        /**
         * Brings the running service in line with the stored dot preference: it is started when the
         * dot should be visible again and stopped when the user turned it off.
         */
        fun syncBubble(context: Context, enabled: Boolean) {
            if (enabled) start(context) else stop(context)
        }

        /**
         * Re-claims the foreground-service types when the microphone permission appeared after the
         * service was already running. Only effective while the app is in the foreground, which is
         * when Android accepts the microphone type.
         */
        fun refreshForegroundTypes() {
            instance?.refreshForegroundTypes()
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
