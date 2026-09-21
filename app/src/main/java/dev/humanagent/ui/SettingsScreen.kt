@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.humanagent.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.humanagent.agent.AgentAccessibilityService
import dev.humanagent.agent.AgentService
import dev.humanagent.llm.AppSettings
import dev.humanagent.llm.ProviderKind
import dev.humanagent.llm.SettingsStore
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Full editor for [AppSettings] plus a live permission/agent status card.
 * Every edit is written to the store immediately (no debounce).
 */
@Composable
fun SettingsScreen(settingsStore: SettingsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loaded = settingsStore.settings.collectAsState(initial = null).value

    if (loaded == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    var baseUrl by remember(loaded.baseUrl) { mutableStateOf(loaded.baseUrl) }
    var apiKey by remember(loaded.apiKey) { mutableStateOf(loaded.apiKey) }
    var model by remember(loaded.model) { mutableStateOf(loaded.model) }
    var persona by remember(loaded.persona) { mutableStateOf(loaded.persona) }
    var maxTokensText by remember(loaded.maxTokens) { mutableStateOf(loaded.maxTokens.toString()) }
    var temperature by remember(loaded.temperature) { mutableStateOf(loaded.temperature.toFloat()) }
    var maxSteps by remember(loaded.maxSteps) { mutableStateOf(loaded.maxSteps.toFloat()) }
    var stepDelay by remember(loaded.stepDelayMs) { mutableStateOf(loaded.stepDelayMs.toFloat()) }

    fun write(transform: (AppSettings) -> AppSettings) {
        scope.launch { settingsStore.update(transform) }
    }

    fun applyProviderKind(kind: ProviderKind) {
        val nextBaseUrl = if (shouldAdoptDefault(loaded.baseUrl, kind) { it.defaultBaseUrl }) {
            kind.defaultBaseUrl
        } else {
            baseUrl
        }
        val nextModel = if (shouldAdoptDefault(loaded.model, kind) { it.defaultModel }) {
            kind.defaultModel
        } else {
            model
        }
        baseUrl = nextBaseUrl
        model = nextModel
        write { current ->
            current.copy(providerKind = kind, baseUrl = nextBaseUrl, model = nextModel)
        }
    }

    val agentRunning by AgentService.isRunning.collectAsState()

    var accessibilityOn by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var overlayOn by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var batteryOn by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    var notificationsOn by remember { mutableStateOf(areNotificationsAllowed(context)) }
    var micOn by remember { mutableStateOf(hasPermission(context, Manifest.permission.RECORD_AUDIO)) }
    var smsOn by remember { mutableStateOf(hasPermission(context, Manifest.permission.SEND_SMS)) }
    var contactsOn by remember { mutableStateOf(hasPermission(context, Manifest.permission.READ_CONTACTS)) }

    fun refreshPermissions() {
        accessibilityOn = isAccessibilityServiceEnabled(context)
        overlayOn = Settings.canDrawOverlays(context)
        batteryOn = isIgnoringBatteryOptimizations(context)
        notificationsOn = areNotificationsAllowed(context)
        micOn = hasPermission(context, Manifest.permission.RECORD_AUDIO)
        smsOn = hasPermission(context, Manifest.permission.SEND_SMS)
        contactsOn = hasPermission(context, Manifest.permission.READ_CONTACTS)
    }

    // Re-read the system state when a settings screen we opened comes back.
    val systemSettingsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshPermissions()
        }
    val runtimePermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshPermissions()
        }
    LaunchedEffect(Unit) { refreshPermissions() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard(title = "Provider") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ProviderKind.entries.forEach { kind ->
                    FilterChip(
                        selected = loaded.providerKind == kind,
                        onClick = { applyProviderKind(kind) },
                        label = { Text(kind.label) },
                    )
                }
            }
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { value ->
                    baseUrl = value
                    write { it.copy(baseUrl = value) }
                },
                label = { Text("Base URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Root of the OpenAI-compatible endpoint, e.g. http://127.0.0.1:11434/v1") },
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { value ->
                    apiKey = value
                    write { it.copy(apiKey = value) }
                },
                label = { Text("API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    Text(
                        if (loaded.providerKind.needsApiKey) {
                            "${loaded.providerKind.label} needs a key"
                        } else {
                            "Not required by ${loaded.providerKind.label}"
                        },
                    )
                },
            )
            OutlinedTextField(
                value = model,
                onValueChange = { value ->
                    model = value
                    write { it.copy(model = value) }
                },
                label = { Text("Model") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    Text(
                        if (loaded.providerKind.defaultModel.isBlank()) {
                            "Name of the model to call"
                        } else {
                            "Default for this provider: ${loaded.providerKind.defaultModel}"
                        },
                    )
                },
            )
        }

        SectionCard(title = "Generation") {
            ValueSlider(
                label = "Temperature",
                valueText = String.format(Locale.US, "%.2f", temperature),
                value = temperature,
                valueRange = 0f..1.5f,
                steps = 0,
                onValueChange = { temperature = it },
                onValueChangeFinished = { write { it.copy(temperature = temperature.toDouble()) } },
            )
            OutlinedTextField(
                value = maxTokensText,
                onValueChange = { raw ->
                    if (raw.length <= 6 && raw.all { it.isDigit() }) {
                        maxTokensText = raw
                        val parsed = raw.toIntOrNull()
                        if (parsed != null && parsed > 0) {
                            write { it.copy(maxTokens = parsed) }
                        }
                    }
                },
                label = { Text("Max tokens") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Upper bound on the length of one reply") },
            )
        }

        SectionCard(title = "Persona") {
            OutlinedTextField(
                value = persona,
                onValueChange = { value ->
                    persona = value
                    write { it.copy(persona = value) }
                },
                label = { Text("System prompt") },
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("How the assistant should talk, and what it may do on the phone") },
            )
        }

        SectionCard(title = "Voice") {
            SwitchRow(
                title = "Speak replies out loud",
                detail = "Reads every assistant reply with the on-device text-to-speech voice.",
                checked = loaded.speakReplies,
                onCheckedChange = { checked -> write { it.copy(speakReplies = checked) } },
            )
        }

        SectionCard(title = "Agent") {
            SwitchRow(
                title = "Attach screenshots",
                detail = "Sends a picture of the screen with every agent step so the model can see it.",
                checked = loaded.sendScreenshots,
                onCheckedChange = { checked -> write { it.copy(sendScreenshots = checked) } },
            )
            ValueSlider(
                label = "Max steps per task",
                valueText = "${maxSteps.roundToInt()}",
                value = maxSteps,
                valueRange = 1f..50f,
                steps = 48,
                onValueChange = { maxSteps = it.roundToInt().toFloat() },
                onValueChangeFinished = { write { it.copy(maxSteps = maxSteps.roundToInt()) } },
            )
            ValueSlider(
                label = "Delay between steps",
                valueText = "${stepDelay.roundToInt()} ms",
                value = stepDelay,
                valueRange = 0f..2000f,
                steps = 19,
                onValueChange = { stepDelay = (it / 100f).roundToInt().coerceIn(0, 20).toFloat() * 100f },
                onValueChangeFinished = { write { it.copy(stepDelayMs = stepDelay.roundToInt()) } },
            )
        }

        SectionCard(title = "Permissions") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Agent service: ${if (agentRunning) "running" else "stopped"}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { refreshPermissions() }) {
                    Icon(imageVector = Icons.Filled.Refresh, contentDescription = "Re-check permissions")
                }
            }
            PermissionRow(
                title = "Accessibility service",
                detail = "Lets the assistant read the screen and tap, type and scroll for you.",
                granted = accessibilityOn,
                actionLabel = "Open",
                onAction = {
                    systemSettingsLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
            )
            PermissionRow(
                title = "Display over other apps",
                detail = "Draws the assistant bubble on top of the app being operated.",
                granted = overlayOn,
                actionLabel = "Open",
                onAction = {
                    systemSettingsLauncher.launch(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.fromParts("package", context.packageName, null),
                        ),
                    )
                },
            )
            PermissionRow(
                title = "Ignore battery optimisation",
                detail = "Stops Android from freezing the assistant in the middle of a task.",
                granted = batteryOn,
                actionLabel = "Open",
                onAction = {
                    systemSettingsLauncher.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                },
            )
            PermissionRow(
                title = "Notifications",
                detail = "Shows the foreground-service status while the agent works.",
                granted = notificationsOn,
                actionLabel = "Allow",
                actionEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsOn,
                onAction = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        runtimePermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
            )
            PermissionRow(
                title = "Microphone",
                detail = "Dictate chat messages and agent commands.",
                granted = micOn,
                actionLabel = "Allow",
                actionEnabled = !micOn,
                onAction = { runtimePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
            )
            PermissionRow(
                title = "Send SMS",
                detail = "Lets the assistant send a text message when you ask it to.",
                granted = smsOn,
                actionLabel = "Allow",
                actionEnabled = !smsOn,
                onAction = { runtimePermissionLauncher.launch(Manifest.permission.SEND_SMS) },
            )
            PermissionRow(
                title = "Contacts",
                detail = "Lets the assistant look a contact up by name.",
                granted = contactsOn,
                actionLabel = "Allow",
                actionEnabled = !contactsOn,
                onAction = { runtimePermissionLauncher.launch(Manifest.permission.READ_CONTACTS) },
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}

@Composable
private fun ValueSlider(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished,
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PermissionRow(
    title: String,
    detail: String,
    granted: Boolean,
    actionLabel: String,
    actionEnabled: Boolean = true,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (granted) "Granted" else "Missing",
                style = MaterialTheme.typography.labelMedium,
                color = if (granted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Button(onClick = onAction, enabled = actionEnabled) {
            Text(text = actionLabel)
        }
    }
}

private fun shouldAdoptDefault(
    current: String,
    newKind: ProviderKind,
    default: (ProviderKind) -> String,
): Boolean {
    if (current.isBlank()) return true
    return ProviderKind.entries.any { kind ->
        kind != newKind && default(kind).isNotBlank() && default(kind) == current
    }
}

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun areNotificationsAllowed(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val component = context.packageName + "/" + AgentAccessibilityService::class.java.name
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    return enabled.split(':').any { it.equals(component, ignoreCase = true) }
}
