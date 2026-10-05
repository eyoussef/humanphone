package dev.humanagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.humanagent.HumanPhoneApp
import dev.humanagent.R
import dev.humanagent.agent.AgentService
import dev.humanagent.agent.AgentStep
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Live view of the on-device agent: service switch, typed/ dictated command, halt button,
 * the stream of [AgentStep]s and the model output as it arrives.
 */
@Composable
fun AgentScreen(engineActive: Boolean) {
    val context = LocalContext.current
    val voice = HumanPhoneApp.instance.voice
    val settingsStore = HumanPhoneApp.instance.settingsStore
    val state by AgentService.loop.collectAsState()
    val serviceRunning by AgentService.isRunning.collectAsState()
    val settings by settingsStore.settings.collectAsState(initial = null)
    val listening by voice.isListening.collectAsState()
    val partial by voice.partial.collectAsState()
    val status by voice.status.collectAsState()
    val scope = rememberCoroutineScope()

    var command by remember { mutableStateOf("") }
    // Only dictation started from this screen's mic is consumed here, and a shared utterance must
    // not be handed to the agent twice.
    var dictatingHere by remember { mutableStateOf(false) }
    val traceState = rememberLazyListState()
    val transcript = state.transcript
    val firstTimestamp = transcript.firstOrNull()?.timestampMs ?: 0L

    LaunchedEffect(transcript.size) {
        if (transcript.isNotEmpty()) traceState.animateScrollToItem(transcript.lastIndex)
    }
    // A dictated command is shown in the field and handed straight to the agent loop.
    LaunchedEffect(voice) {
        voice.heard.collect { utterance ->
            val text = utterance.trim()
            if (dictatingHere && text.isNotEmpty()) {
                dictatingHere = false
                command = text
                AgentService.run(context, text)
            }
        }
    }
    // A dictation that produced nothing (cancelled, silent, mic error) releases the claim.
    LaunchedEffect(listening) {
        if (!listening) dictatingHere = false
    }

    fun submitCommand() {
        val text = command.trim()
        if (text.isNotEmpty()) {
            AgentService.run(context, text)
            command = ""
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.agent_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.agent_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.agent_service),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = if (serviceRunning) {
                        stringResource(R.string.service_running)
                    } else {
                        stringResource(R.string.service_stopped)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (serviceRunning) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Switch(
                checked = serviceRunning,
                onCheckedChange = { wanted ->
                    if (wanted) AgentService.start(context) else AgentService.stop(context)
                },
            )
        }

        // Auto mode: the agent watches notifications and answers the ones that need a reply.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.auto_mode), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(R.string.auto_mode_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings?.autoMode == true,
                onCheckedChange = { wanted ->
                    // Enabling Auto mode puts the monitor in the background right away — the
                    // watchdog listens (and answers) without the user ever typing a command.
                    if (wanted) AgentService.start(context)
                    scope.launch { settingsStore.update { it.copy(autoMode = wanted) } }
                },
            )
        }

        Text(
            text = if (engineActive) {
                stringResource(R.string.agent_engine_streaming)
            } else if (state.running) {
                stringResource(R.string.working_on_phone)
            } else {
                stringResource(R.string.idle_state)
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )

        if (state.liveText.isNotBlank() || state.running) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.running && state.liveText.isBlank()) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = stringResource(R.string.model_output),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    if (state.liveText.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = state.liveText,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.command_label)) },
                placeholder = { Text(stringResource(R.string.command_hint)) },
                maxLines = 3,
                supportingText = {
                    val voiceStatus = status
                    val hint = when {
                        voiceStatus.isNotBlank() -> voiceStatus
                        listening -> if (partial.isBlank()) {
                            stringResource(R.string.status_listening)
                        } else {
                            partial
                        }
                        else -> ""
                    }
                    if (hint.isNotBlank()) Text(hint)
                },
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = {
                    if (listening) {
                        dictatingHere = false
                        voice.stopListening()
                    } else {
                        dictatingHere = true
                        voice.startListening()
                    }
                },
            ) {
                Icon(
                    imageVector = if (listening) Icons.Filled.MicOff else Icons.Filled.Mic,
                    contentDescription = if (listening) {
                        stringResource(R.string.stop_dictating)
                    } else {
                        stringResource(R.string.dictate_command)
                    },
                    tint = if (listening) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { submitCommand() },
                enabled = command.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = stringResource(R.string.run_task))
            }
            OutlinedButton(
                onClick = { AgentService.halt(context) },
                enabled = state.running,
            ) {
                Icon(
                    imageVector = Icons.Filled.Stop,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = stringResource(R.string.halt_task))
            }
        }

        val visibleError = state.error?.takeIf { it.isNotBlank() }
        if (visibleError != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = visibleError, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (state.lastReply.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = stringResource(R.string.last_reply),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Text(text = state.lastReply, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = stringResource(R.string.trace_title),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        if (transcript.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.no_steps_yet),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else {
            LazyColumn(
                state = traceState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(transcript) { step ->
                    TraceRow(step = step, baseTimestamp = firstTimestamp)
                }
            }
        }
    }
}

@Composable
private fun TraceRow(step: AgentStep, baseTimestamp: Long) {
    val elapsedSeconds = (step.timestampMs - baseTimestamp).coerceAtLeast(0L) / 1000.0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Text(
            text = "#${step.index}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(40.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = step.title.ifBlank { step.kind },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = step.kind,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            if (step.detail.isNotBlank()) {
                Text(
                    text = step.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = "+" + String.format(Locale.US, "%.1fs", elapsedSeconds),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(52.dp),
        )
    }
}
