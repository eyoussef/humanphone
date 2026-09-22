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
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
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
import androidx.core.content.ContextCompat
import dev.humanagent.agent.AgentAccessibilityService
import dev.humanagent.agent.AgentService
import dev.humanagent.HumanPhoneApp
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
    var speechRate by remember(loaded.ttsSpeechRate) { mutableStateOf(loaded.ttsSpeechRate) }
    var pitch by remember(loaded.ttsPitch) { mutableStateOf(loaded.ttsPitch) }
    var sttBaseUrl by remember(loaded.sttBaseUrl) { mutableStateOf(loaded.sttBaseUrl) }
    var sttApiKey by remember(loaded.sttApiKey) { mutableStateOf(loaded.sttApiKey) }
    var sttModel by remember(loaded.sttModel) { mutableStateOf(loaded.sttModel) }

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
    var accessibilityConnected by remember { mutableStateOf(AgentAccessibilityService.isConnected()) }
    var overlayOn by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var batteryOn by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    var notificationsOn by remember { mutableStateOf(areNotificationsAllowed(context)) }
    var micOn by remember { mutableStateOf(hasPermission(context, Manifest.permission.RECORD_AUDIO)) }
    var smsOn by remember { mutableStateOf(hasPermission(context, Manifest.permission.SEND_SMS)) }
    var contactsOn by remember { mutableStateOf(hasPermission(context, Manifest.permission.READ_CONTACTS)) }

    fun refreshPermissions() {
        accessibilityOn = isAccessibilityServiceEnabled(context)
        accessibilityConnected = AgentAccessibilityService.isConnected()
        overlayOn = Settings.canDrawOverlays(context)
        batteryOn = isIgnoringBatteryOptimizations(context)
        notificationsOn = areNotificationsAllowed(context)
        micOn = hasPermission(context, Manifest.permission.RECORD_AUDIO)
        smsOn = hasPermission(context, Manifest.permission.SEND_SMS)
        contactsOn = hasPermission(context, Manifest.permission.READ_CONTACTS)
        // A microphone permission granted here also lets the running service claim the microphone
        // foreground type it needs for dictation from the floating dot.
        if (micOn) AgentService.refreshForegroundTypes()
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

    /**
     * Android 13+ greys out Accessibility and other special access for sideloaded apps until the
     * user allows restricted settings from the app's info page; this opens that page. It is also
     * the only way back once a runtime permission has been denied twice.
     */
    fun openAppInfo() {
        systemSettingsLauncher.launch(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ),
        )
    }

    fun openAppNotificationSettings() {
        systemSettingsLauncher.launch(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        )
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
                    // An API key is one line: a pasted line break is dropped instead of stored.
                    val clean = value.filterNot { it == '\n' || it == '\r' }
                    apiKey = clean
                    write { it.copy(apiKey = clean) }
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
            ValueSlider(
                label = "Speech rate",
                valueText = String.format(Locale.US, "%.2f×", speechRate),
                value = speechRate,
                valueRange = 0.5f..2f,
                steps = 14,
                onValueChange = { speechRate = it },
                onValueChangeFinished = { write { it.copy(ttsSpeechRate = speechRate) } },
            )
            ValueSlider(
                label = "Voice pitch",
                valueText = String.format(Locale.US, "%.2f", pitch),
                value = pitch,
                valueRange = 0.5f..2f,
                steps = 14,
                onValueChange = { pitch = it },
                onValueChangeFinished = { write { it.copy(ttsPitch = pitch) } },
            )
        }

        SectionCard(title = "Voice notes") {
            OutlinedTextField(
                value = sttBaseUrl,
                onValueChange = { value ->
                    sttBaseUrl = value
                    write { it.copy(sttBaseUrl = value) }
                },
                label = { Text("Speech-to-text endpoint") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    Text(
                        "Root of an OpenAI-compatible /audio/transcriptions service — Groq, OpenAI or a " +
                            "whisper server on this phone. Empty leaves voice notes untranscribed.",
                    )
                },
            )
            OutlinedTextField(
                value = sttApiKey,
                onValueChange = { value ->
                    // An API key is one line: a pasted line break is dropped instead of stored.
                    val clean = value.filterNot { it == '\n' || it == '\r' }
                    sttApiKey = clean
                    write { it.copy(sttApiKey = clean) }
                },
                label = { Text("Speech-to-text API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Only a hosted service needs one; a server on this phone does not.") },
            )
            OutlinedTextField(
                value = sttModel,
                onValueChange = { value ->
                    sttModel = value
                    write { it.copy(sttModel = value) }
                },
                label = { Text("Transcription model") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("For example whisper-1, or whisper-large-v3-turbo on Groq.") },
            )
        }

        SectionCard(title = "Language & voices") {
            val deviceLanguages = remember { deviceLanguageTags() }
            val shownDeviceLanguages = remember(deviceLanguages) {
                deviceLanguages.take(MAX_LANGUAGE_OPTIONS)
            }
            // The engine's own list: it arrives once the speech engine is up and is refreshed on
            // demand, so opening this screen never waits on the text-to-speech service.
            val ttsLanguages by HumanPhoneApp.instance.speaker.availableLanguagesFlow.collectAsState()

            LanguagePicker(
                label = "Speech input language",
                selected = loaded.sttLanguage,
                options = shownDeviceLanguages,
                onSelected = { tag -> write { it.copy(sttLanguage = tag) } },
            )
            SwitchRow(
                title = "Prefer offline recognition",
                detail = "Transcribes without a network connection when the installed recogniser can.",
                checked = loaded.sttPreferOffline,
                onCheckedChange = { checked -> write { it.copy(sttPreferOffline = checked) } },
            )
            if (deviceLanguages.size > MAX_LANGUAGE_OPTIONS) {
                Text(
                    text = "Listing the first $MAX_LANGUAGE_OPTIONS of ${deviceLanguages.size} " +
                        "languages this device knows.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LanguagePicker(
                    label = "Spoken replies language",
                    selected = loaded.ttsLanguage,
                    options = ttsLanguages,
                    onSelected = { tag -> write { it.copy(ttsLanguage = tag) } },
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { HumanPhoneApp.instance.speaker.refreshLanguages() },
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Re-check the voices installed on this device",
                    )
                }
            }
            if (ttsLanguages.isEmpty()) {
                Text(
                    text = "No installed voices listed yet — the speech engine may still be starting up.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        runCatching {
                            systemSettingsLauncher.launch(
                                Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA),
                            )
                        }
                    },
                ) {
                    Text(text = "Download voices")
                }
                OutlinedButton(
                    onClick = {
                        runCatching {
                            systemSettingsLauncher.launch(Intent(TTS_SETTINGS_ACTION))
                        }
                    },
                ) {
                    Text(text = "TTS settings")
                }
                OutlinedButton(
                    onClick = {
                        runCatching {
                            systemSettingsLauncher.launch(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                        }
                    },
                ) {
                    Text(text = "Download offline speech")
                }
            }

            SwitchRow(
                title = "Show the floating dot",
                detail = "Keeps the assistant dot over other apps while the agent service runs.",
                checked = loaded.showBubble,
                onCheckedChange = { checked ->
                    write { it.copy(showBubble = checked) }
                    // Starting or stopping the service here means the dot appears (or goes away)
                    // the moment the switch moves, instead of on the next app start.
                    AgentService.syncBubble(context, checked)
                },
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

        SectionCard(title = "Trouble enabling a permission?") {
            Text(
                text = "1. Android hides special access such as Accessibility for apps installed outside a store.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "2. Tap Open app info, then the ⋮ menu at the top right, then Allow restricted settings.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "3. Come back here and turn the permission on again.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { openAppInfo() }) {
                Text(text = "Open app info")
            }
        }

        SectionCard(title = "Permissions") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Agent service: ${if (agentRunning) "running" else "stopped"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = when {
                            !accessibilityOn -> "Accessibility service: disabled"
                            accessibilityConnected -> "Accessibility service: connected"
                            else -> "Accessibility service: enabled, waiting to connect"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                warning = if (accessibilityOn && !accessibilityConnected) {
                    "Enabled but not connected — turn it off and on again, or reopen the app."
                } else {
                    null
                },
                secondaryActionLabel = "App info",
                onSecondaryAction = { openAppInfo() },
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
                actionEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                actionVisible = !notificationsOn,
                onAction = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        runtimePermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                secondaryActionLabel = "App info",
                onSecondaryAction = { openAppNotificationSettings() },
            )
            PermissionRow(
                title = "Microphone",
                detail = "Dictate chat messages and agent commands.",
                granted = micOn,
                actionLabel = "Allow",
                actionVisible = !micOn,
                onAction = { runtimePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                secondaryActionLabel = "App info",
                onSecondaryAction = { openAppInfo() },
            )
            PermissionRow(
                title = "Send SMS",
                detail = "Lets the assistant send a text message when you ask it to.",
                granted = smsOn,
                actionLabel = "Allow",
                actionVisible = !smsOn,
                onAction = { runtimePermissionLauncher.launch(Manifest.permission.SEND_SMS) },
                secondaryActionLabel = "App info",
                onSecondaryAction = { openAppInfo() },
            )
            PermissionRow(
                title = "Contacts",
                detail = "Lets the assistant look a contact up by name.",
                granted = contactsOn,
                actionLabel = "Allow",
                actionVisible = !contactsOn,
                onAction = { runtimePermissionLauncher.launch(Manifest.permission.READ_CONTACTS) },
                secondaryActionLabel = "App info",
                onSecondaryAction = { openAppInfo() },
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
private fun LanguagePicker(
    label: String,
    selected: String,
    options: List<String>,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selected.toLanguageLabel(),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(text = label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(text = DEVICE_DEFAULT_LABEL) },
                onClick = {
                    expanded = false
                    onSelected("")
                },
            )
            options.forEach { tag ->
                DropdownMenuItem(
                    text = { Text(text = tag.toLanguageLabel()) },
                    onClick = {
                        expanded = false
                        onSelected(tag)
                    },
                )
            }
        }
    }
}

/**
 * A tag as the user reads it: the locale's own name followed by the tag the engine understands,
 * or the device default when the tag is empty.
 */
private fun String.toLanguageLabel(): String {
    if (isBlank()) return DEVICE_DEFAULT_LABEL
    return runCatching {
        val name = Locale.forLanguageTag(this).getDisplayName(Locale.getDefault())
        if (name.isBlank() || name.equals(this, ignoreCase = true)) this else "$name · $this"
    }.getOrDefault(this)
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
    actionVisible: Boolean = true,
    warning: String? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
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
            if (warning != null) {
                Text(
                    text = warning,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (actionVisible) {
            Spacer(modifier = Modifier.width(8.dp))
            val secondaryLabel = secondaryActionLabel
            val secondaryAction = onSecondaryAction
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (secondaryLabel != null && secondaryAction != null) {
                    // Two actions must fit next to each other, so both go compact.
                    Button(
                        onClick = onAction,
                        enabled = actionEnabled,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(text = actionLabel)
                    }
                    OutlinedButton(
                        onClick = secondaryAction,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(text = secondaryLabel)
                    }
                } else {
                    Button(onClick = onAction, enabled = actionEnabled) {
                        Text(text = actionLabel)
                    }
                }
            }
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

/** Distinct BCP-47 tags for every locale this device knows, sorted. */
private fun deviceLanguageTags(): List<String> =
    Locale.getAvailableLocales()
        .mapNotNull { locale -> runCatching { locale.toLanguageTag() }.getOrNull() }
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()

/** The entry that clears a language back to whatever the device uses. */
private const val DEVICE_DEFAULT_LABEL = "Device default"

/** Hundreds of entries make a dropdown unusable, so the input list stops here. */
private const val MAX_LANGUAGE_OPTIONS = 120

/** Action of the system text-to-speech settings page. */
private const val TTS_SETTINGS_ACTION = "com.android.settings.TTS_SETTINGS"

