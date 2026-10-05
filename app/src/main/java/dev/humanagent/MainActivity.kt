package dev.humanagent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import dev.humanagent.agent.AgentService
import dev.humanagent.ui.AgentScreen
import dev.humanagent.ui.ChatScreen
import dev.humanagent.ui.HumanPhoneTheme
import dev.humanagent.ui.SettingsScreen

private const val TAB_CHAT = 0
private const val TAB_AGENT = 1
private const val TAB_SETTINGS = 2

/**
 * Single activity host: bottom navigation between chat, agent and settings, the notification
 * permission prompt, and the assistant service lifecycle.
 */
class MainActivity : ComponentActivity() {

    private val tabState: MutableState<Int> = mutableStateOf(TAB_CHAT)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Draw behind the status and navigation bars; the Scaffold adds those insets back.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // The service hosts the on-device agent loop; the launcher opens straight into the app.
        if (!AgentService.isRunning.value) {
            AgentService.start(this)
        }

        setContent {
            val tab = tabState.value
            HumanPhoneTheme {
                val phone = HumanPhoneApp.instance
                Scaffold(
                    bottomBar = {
                        BottomBar(selected = tab, onSelect = { tabState.value = it })
                    },
                ) { insets ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(insets),
                    ) {
                        when (tab) {
                            TAB_AGENT -> AgentScreen(
                                engineActive = phone.chatEngine.streaming.collectAsState().value,
                            )

                            TAB_SETTINGS -> SettingsScreen(settingsStore = phone.settingsStore)

                            else -> ChatScreen(
                                engine = phone.chatEngine,
                                voice = phone.voice,
                                speaker = phone.speaker,
                                onOpenSettings = { tabState.value = TAB_SETTINGS },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Both the assistant gesture and a launcher tap land on the chat screen.
        when (intent.action) {
            Intent.ACTION_ASSIST, Intent.ACTION_MAIN -> tabState.value = TAB_CHAT
            else -> Unit
        }
    }

    override fun onDestroy() {
        // Auto mode keeps the monitor alive after the app is gone; only a plain exit stops it.
        if (isFinishing && !AgentService.autoMode.value) AgentService.stop(this)
        super.onDestroy()
    }
}

@Composable
private fun BottomBar(selected: Int, onSelect: (Int) -> Unit) {
    NavigationBar {
        NavigationBarItem(
            selected = selected == TAB_CHAT,
            onClick = { onSelect(TAB_CHAT) },
            icon = { Icon(imageVector = Icons.Filled.Chat, contentDescription = null) },
            label = { Text(text = stringResource(R.string.tab_chat)) },
        )
        NavigationBarItem(
            selected = selected == TAB_AGENT,
            onClick = { onSelect(TAB_AGENT) },
            icon = { Icon(imageVector = Icons.Filled.SmartToy, contentDescription = null) },
            label = { Text(text = stringResource(R.string.tab_agent)) },
        )
        NavigationBarItem(
            selected = selected == TAB_SETTINGS,
            onClick = { onSelect(TAB_SETTINGS) },
            icon = { Icon(imageVector = Icons.Filled.Settings, contentDescription = null) },
            label = { Text(text = stringResource(R.string.tab_settings)) },
        )
    }
}
