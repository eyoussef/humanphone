@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.humanagent.ui

import android.Manifest
import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.LocaleList
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.humanagent.HumanPhoneApp
import dev.humanagent.R
import dev.humanagent.agent.AgentAccessibilityService
import dev.humanagent.agent.AgentService
import dev.humanagent.llm.AppSettings
import dev.humanagent.llm.ProviderKind
import dev.humanagent.llm.SettingsStore
import dev.humanagent.llm.SttMode
import dev.humanagent.llm.TtsMode
import dev.humanagent.voice.TtsEngineEntry
import dev.humanagent.voice.installedTtsEngines
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
    val voice = HumanPhoneApp.instance.voice
    val speaker = HumanPhoneApp.instance.speaker
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
    var autoPersona by remember(loaded.autoModePersona) { mutableStateOf(loaded.autoModePersona) }
    var maxTokensText by remember(loaded.maxTokens) { mutableStateOf(loaded.maxTokens.toString()) }
    var temperature by remember(loaded.temperature) { mutableStateOf(loaded.temperature.toFloat()) }
    var maxSteps by remember(loaded.maxSteps) { mutableStateOf(loaded.maxSteps.toFloat()) }
    var stepDelay by remember(loaded.stepDelayMs) { mutableStateOf(loaded.stepDelayMs.toFloat()) }
    var sttBaseUrl by remember(loaded.sttBaseUrl) { mutableStateOf(loaded.sttBaseUrl) }
    var sttApiKey by remember(loaded.sttApiKey) { mutableStateOf(loaded.sttApiKey) }
    var sttModel by remember(loaded.sttModel) { mutableStateOf(loaded.sttModel) }
    var sttLanguage by remember(loaded.sttLanguage) { mutableStateOf(loaded.sttLanguage) }
    var ttsBaseUrl by remember(loaded.ttsBaseUrl) { mutableStateOf(loaded.ttsBaseUrl) }
    var ttsApiKey by remember(loaded.ttsApiKey) { mutableStateOf(loaded.ttsApiKey) }
    var ttsModel by remember(loaded.ttsModel) { mutableStateOf(loaded.ttsModel) }
    var ttsVoice by remember(loaded.ttsVoice) { mutableStateOf(loaded.ttsVoice) }
    var showClearMemoryDialog by remember { mutableStateOf(false) }

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
    var batteryOn by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    var notificationsOn by remember { mutableStateOf(areNotificationsAllowed(context)) }
    var micOn by remember { mutableStateOf(hasPermission(context, Manifest.permission.RECORD_AUDIO)) }
    var smsOn by remember { mutableStateOf(hasPermission(context, Manifest.permission.SEND_SMS)) }
    var contactsOn by remember { mutableStateOf(hasPermission(context, Manifest.permission.READ_CONTACTS)) }

    fun refreshPermissions() {
        accessibilityOn = isAccessibilityServiceEnabled(context)
        accessibilityConnected = AgentAccessibilityService.isConnected()
        batteryOn = isIgnoringBatteryOptimizations(context)
        notificationsOn = areNotificationsAllowed(context)
        micOn = hasPermission(context, Manifest.permission.RECORD_AUDIO)
        smsOn = hasPermission(context, Manifest.permission.SEND_SMS)
        contactsOn = hasPermission(context, Manifest.permission.READ_CONTACTS)
    }

    // Re-read the system state when a settings screen we opened comes back.
    var engineTick by remember { mutableStateOf(0) }
    val systemSettingsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            engineTick++
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
        SectionCard(title = stringResource(R.string.language_title)) {
            var showLanguageDialog by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.app_language),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = languageOptions
                            .firstOrNull { it.first == loaded.language }
                            ?.second
                            ?: stringResource(R.string.language_system_default),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = { showLanguageDialog = true }) {
                    Text(stringResource(R.string.change_language))
                }
            }
            if (showLanguageDialog) {
                AlertDialog(
                    onDismissRequest = { showLanguageDialog = false },
                    title = { Text(stringResource(R.string.app_language)) },
                    text = {
                        Column {
                            languageOptions.forEach { (tag, label) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            showLanguageDialog = false
                                            write { it.copy(language = tag) }
                                            applyAppLanguage(context, tag)
                                        },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        selected = loaded.language == tag,
                                        onClick = {
                                            showLanguageDialog = false
                                            write { it.copy(language = tag) }
                                            applyAppLanguage(context, tag)
                                        },
                                    )
                                    Text(
                                        text = if (tag.isBlank()) {
                                            stringResource(R.string.language_system_default)
                                        } else {
                                            label
                                        },
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showLanguageDialog = false }) {
                            Text(stringResource(R.string.done))
                        }
                    },
                )
            }
        }

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
            if (loaded.baseUrl.startsWith("http://", ignoreCase = true) && loaded.apiKey.isNotBlank()) {
                Text(
                    text = "This endpoint is plain http, so the API key travels unencrypted on your network. That is expected for a local server on your own Wi-Fi; for anything beyond it, prefer an https endpoint.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
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

        SectionCard(title = "Auto mode") {
            Text(
                text = "While Auto mode is on, the agent keeps running in the background and listens to the " +
                    "phone's notifications. When a person really needs a reply it opens the app the message " +
                    "arrived in, sends a short answer on your behalf and goes back to listening. It never " +
                    "answers one-time codes, promotions or machine notices; anything unclear is skipped.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = autoPersona,
                onValueChange = { value ->
                    autoPersona = value
                    write { it.copy(autoModePersona = value) }
                },
                label = { Text("What Auto mode may answer") },
                minLines = 2,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    Text(
                        "Optional persona that limits or specializes it: e.g. \"only messages from my " +
                            "family, in Arabic\", \"answer work e-mail during office hours\", \"never take part in group chats\"",
                    )
                },
            )
        }

        SectionCard(title = "Voice") {
            SwitchRow(
                title = "Speak replies out loud",
                detail = "Reads every assistant reply with the chosen voice.",
                checked = loaded.speakReplies,
                onCheckedChange = { checked -> write { it.copy(speakReplies = checked) } },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TtsMode.entries.forEach { mode ->
                    FilterChip(
                        selected = loaded.ttsMode == mode,
                        onClick = { write { it.copy(ttsMode = mode) } },
                        label = { Text(mode.label) },
                    )
                }
            }
            // The engines installed on this phone; the choice survives even when Android has no default set.
            val engines = remember(engineTick) { installedTtsEngines(context) }
            val engineChoices = engines +
                (if (loaded.ttsEngine.isNotBlank() && engines.none { it.packageName == loaded.ttsEngine }) {
                    listOf(TtsEngineEntry(label = loaded.ttsEngine, packageName = loaded.ttsEngine))
                } else {
                    emptyList()
                })
            if (engineChoices.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = loaded.ttsEngine.isBlank(),
                        onClick = { write { it.copy(ttsEngine = "") } },
                        label = { Text("System default") },
                    )
                    engineChoices.forEach { choice ->
                        FilterChip(
                            selected = loaded.ttsEngine == choice.packageName,
                            onClick = { write { it.copy(ttsEngine = choice.packageName) } },
                            label = { Text(choice.label) },
                        )
                    }
                }
            }
            // What the phone's own engine can do right now; refreshed when Android's own screens return.
            val engineStatus = remember(engineTick) { speaker.ttsStatus() }
            Text(
                text = when {
                    !engineStatus.ready && loaded.ttsMode == TtsMode.REMOTE ->
                        "On-device voice: ${engineStatus.problem ?: "not ready"} (remote is active)."
                    loaded.ttsMode == TtsMode.REMOTE ->
                        "On-device voice \"${engineStatus.engineLabel.ifBlank { "on-device" }}\" is ready (remote is active)."
                    engineStatus.problem != null ->
                        "On-device voice: ${engineStatus.problem}"
                    engineStatus.languageAvailable ->
                        "On-device voice \"${engineStatus.engineLabel.ifBlank { "on-device" }}\" is ready for ${speechLanguageName(loaded.sttLanguage)}."
                    else ->
                        "On-device voice \"${engineStatus.engineLabel.ifBlank { "on-device" }}\" is missing data for ${speechLanguageName(loaded.sttLanguage)}."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            var installMessage by remember { mutableStateOf<String?>(null) }
            OutlinedButton(
                onClick = {
                    val intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                    // Aim the installer at the chosen engine; unset targets the system default.
                    if (loaded.ttsEngine.isNotBlank()) intent.setPackage(loaded.ttsEngine)
                    runCatching { systemSettingsLauncher.launch(intent) }
                        .onFailure {
                            installMessage = if (loaded.ttsEngine.isNotBlank()) {
                                "This engine offers no data-install screen; pick another engine and try again."
                            } else {
                                "No default TTS engine is set on this phone; pick one of the engines first."
                            }
                        }
                },
            ) {
                Text(text = "Install voice data")
            }
            installMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (loaded.ttsMode == TtsMode.REMOTE) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ttsPresets.forEach { preset ->
                        FilterChip(
                            selected = ttsBaseUrl == preset.baseUrl && ttsModel == preset.model && ttsVoice == preset.voice,
                            onClick = {
                                ttsBaseUrl = preset.baseUrl
                                ttsModel = preset.model
                                ttsVoice = preset.voice
                                write {
                                    it.copy(ttsBaseUrl = preset.baseUrl, ttsModel = preset.model, ttsVoice = preset.voice)
                                }
                            },
                            label = { Text(preset.label) },
                        )
                    }
                }
                OutlinedTextField(
                    value = ttsBaseUrl,
                    onValueChange = { value ->
                        ttsBaseUrl = value
                        write { it.copy(ttsBaseUrl = value) }
                    },
                    label = { Text("Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text("Root of the OpenAI-compatible endpoint; replies are posted to /audio/speech")
                    },
                )
                OutlinedTextField(
                    value = ttsApiKey,
                    onValueChange = { value ->
                        ttsApiKey = value
                        write { it.copy(ttsApiKey = value) }
                    },
                    label = { Text("API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Optional for a local server") },
                )
                OutlinedTextField(
                    value = ttsModel,
                    onValueChange = { value ->
                        ttsModel = value
                        write { it.copy(ttsModel = value) }
                    },
                    label = { Text("Speech model") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = ttsVoice,
                    onValueChange = { value ->
                        ttsVoice = value
                        write { it.copy(ttsVoice = value) }
                    },
                    label = { Text("Voice") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Name of the voice, e.g. alloy, nova or Arista-PlayAI") },
                )
            } else {
                Text(
                    text = "Replies use the phone's own voice; it speaks the language set under Speech to text.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            var testingVoice by remember { mutableStateOf(false) }
            var testResult by remember { mutableStateOf<String?>(null) }
            OutlinedButton(
                enabled = !testingVoice,
                onClick = {
                    testingVoice = true
                    testResult = null
                    scope.launch {
                        val outcome = HumanPhoneApp.instance.speaker.probe("Testing the HumanPhone voice.")
                        testResult = outcome.exceptionOrNull()?.message ?: "Playing…"
                        testingVoice = false
                    }
                },
            ) {
                if (testingVoice) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(text = "Test voice")
            }
            testResult?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionCard(title = "Speech to text") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SttMode.entries.forEach { mode ->
                    FilterChip(
                        selected = loaded.sttMode == mode,
                        onClick = { write { it.copy(sttMode = mode) } },
                        label = { Text(mode.label) },
                    )
                }
            }
            if (loaded.sttMode == SttMode.REMOTE) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    sttPresets.forEach { preset ->
                        FilterChip(
                            selected = sttBaseUrl == preset.baseUrl && sttModel == preset.model,
                            onClick = {
                                sttBaseUrl = preset.baseUrl
                                sttModel = preset.model
                                write { it.copy(sttBaseUrl = preset.baseUrl, sttModel = preset.model) }
                            },
                            label = { Text(preset.label) },
                        )
                    }
                }
                OutlinedTextField(
                    value = sttBaseUrl,
                    onValueChange = { value ->
                        sttBaseUrl = value
                        write { it.copy(sttBaseUrl = value) }
                    },
                    label = { Text("Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text("Root of the OpenAI-compatible endpoint; recorded audio is posted to /audio/transcriptions")
                    },
                )
                OutlinedTextField(
                    value = sttApiKey,
                    onValueChange = { value ->
                        sttApiKey = value
                        write { it.copy(sttApiKey = value) }
                    },
                    label = { Text("API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Optional for a local server") },
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
                )
            } else {
                val sttStatusNow = remember(engineTick) { voice.sttStatus() }
                Text(
                    text = when {
                        sttStatusNow.recognitionAvailable ->
                            "Dictation uses the phone's own recogniser, which is available."
                        else ->
                            "The phone has no speech recogniser; use \"Remote Whisper API\" with an endpoint, or install one."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = {
                        systemSettingsLauncher.launch(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                    },
                ) {
                    Text(text = "Voice input settings")
                }
            }
            OutlinedTextField(
                value = sttLanguage,
                onValueChange = { value ->
                    sttLanguage = value
                    write { it.copy(sttLanguage = value) }
                },
                label = { Text("Language") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    Text(
                        "Optional language code such as en or ar; the recogniser listens in it and it steers the on-device voice. Empty follows the system language.",
                    )
                },
            )
            // Runs the same dictation path the app uses everywhere, straight from Settings.
            val listening = voice.isListening.collectAsState().value
            val partialNow = voice.partial.collectAsState().value
            val statusNow = voice.status.collectAsState().value
            var heardNow by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                voice.heard.collect { utterance ->
                    if (utterance.isNotBlank()) heardNow = utterance
                }
            }
            OutlinedButton(
                onClick = {
                    if (listening) {
                        voice.stopListening()
                    } else {
                        heardNow = null
                        voice.startListening()
                    }
                },
            ) {
                if (listening) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(text = if (listening) "Stop listening" else "Test listening")
            }
            val liveHint = when {
                statusNow.isNotBlank() -> statusNow
                listening && !partialNow.isBlank() -> partialNow
                listening -> stringResource(R.string.status_listening)
                else -> ""
            }
            if (liveHint.isNotBlank()) {
                Text(
                    text = liveHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            heardNow?.let { heard ->
                Text(
                    text = "Heard: \"$heard\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }

        SectionCard(title = "Agent") {
            SwitchRow(
                title = "Send texts directly",
                detail = "Off, a send stops in the messaging app with the message pre-filled and you press send — safe and permission-free. On (and with the SMS permission granted), the assistant sends without stopping.",
                checked = loaded.directSms,
                onCheckedChange = { checked -> write { it.copy(directSms = checked) } },
            )
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

        SectionCard(title = stringResource(R.string.memory_title)) {
            val memory = HumanPhoneApp.instance.memory
            val notes = memory.snapshot()
            val people = memory.people()
            if (notes.isEmpty() && people.isEmpty()) {
                Text(
                    text = stringResource(R.string.memory_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                if (notes.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.memory_facts),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                    )
                    notes.forEach { (key, value) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = key,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = value,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { scope.launch { memory.removeFact(key) } }) {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = stringResource(R.string.memory_delete_note),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (people.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.memory_people),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                    people.forEach { person ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = person.name + (person.relation.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = (person.channels + person.notes).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(
                                onClick = { scope.launch { memory.forgetPerson(person.name) } },
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = stringResource(R.string.memory_delete_person),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                OutlinedButton(onClick = { showClearMemoryDialog = true }) {
                    Text(stringResource(R.string.memory_clear_all))
                }
            }
            if (showClearMemoryDialog) {
                AlertDialog(
                    onDismissRequest = { showClearMemoryDialog = false },
                    title = { Text(stringResource(R.string.memory_clear_all)) },
                    text = { Text(stringResource(R.string.memory_clear_confirm)) },
                    confirmButton = {
                        TextButton(onClick = {
                            showClearMemoryDialog = false
                            scope.launch { memory.clearFacts() }
                        }) { Text(stringResource(R.string.memory_clear_all)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showClearMemoryDialog = false }) { Text(stringResource(R.string.done)) }
                    },
                )
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
                detail = "Dictate chat messages and agent commands; remote mode uploads a short recording to the configured endpoint.",
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

/** One-tap endpoint and model pairs for the transcription providers people actually use. */
private val sttPresets = listOf(
    SttPreset(label = "OpenAI", baseUrl = "https://api.openai.com/v1", model = "whisper-1"),
    SttPreset(label = "Groq", baseUrl = "https://api.groq.com/openai/v1", model = "whisper-large-v3-turbo"),
    SttPreset(label = "Local server", baseUrl = "http://127.0.0.1:8080/v1", model = "whisper-1"),
)

private data class SttPreset(val label: String, val baseUrl: String, val model: String)

/** One-tap endpoint, model and voice for the speech providers people actually use. */
private val ttsPresets = listOf(
    TtsPreset(label = "OpenAI", baseUrl = "https://api.openai.com/v1", model = "gpt-4o-mini-tts", voice = "alloy"),
    TtsPreset(label = "Groq", baseUrl = "https://api.groq.com/openai/v1", model = "playai-tts", voice = "Arista-PlayAI"),
    TtsPreset(label = "Local server", baseUrl = "http://127.0.0.1:8080/v1", model = "tts-1", voice = "alloy"),
)

private data class TtsPreset(val label: String, val baseUrl: String, val model: String, val voice: String)

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** The speech language as people say it ("the system language" when none is configured). */
private fun speechLanguageName(tag: String): String {
    val trimmed = tag.trim()
    if (trimmed.isEmpty()) return "the system language"
    val locale = Locale.forLanguageTag(trimmed)
    return locale.displayLanguage.ifBlank { trimmed }
}

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

/** Languages the interface ships with; shown in their own alphabet, so they never need translating. */
private val languageOptions: List<Pair<String, String>> = listOf(
    "" to "System default",
    "en" to "English",
    "ar" to "العربية",
    "fr" to "Français",
    "es" to "Español",
    "pt" to "Português",
    "hi" to "हिन्दी",
)

/**
 * Applies the per-app interface language. Android 13+ keeps the choice in the system and recreates
 * the app right away; on older releases the app stays on the system language.
 */
private fun applyAppLanguage(context: Context, languageTag: String) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    runCatching {
        val localeManager = context.getSystemService(LocaleManager::class.java) ?: return
        localeManager.applicationLocales = if (languageTag.isBlank()) {
            LocaleList.getEmptyLocaleList()
        } else {
            LocaleList.forLanguageTags(languageTag)
        }
    }
}
