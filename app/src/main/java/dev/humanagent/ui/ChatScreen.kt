@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.humanagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.humanagent.HumanPhoneApp
import dev.humanagent.chat.ChatEngine
import dev.humanagent.chat.ChatTurn
import dev.humanagent.voice.Speaker
import dev.humanagent.voice.VoiceIO
import kotlinx.coroutines.launch

/**
 * The conversation screen: header with the active model, conversation switcher, message list,
 * error banner, streaming/listening status and the input row (text, dictation, send/cancel).
 */
@Composable
fun ChatScreen(
    engine: ChatEngine,
    voice: VoiceIO,
    speaker: Speaker,
    onOpenSettings: () -> Unit,
) {
    val settingsStore = HumanPhoneApp.instance.settingsStore
    val settings by settingsStore.settings.collectAsState(initial = null)
    val messages by engine.messages.collectAsState()
    val streaming by engine.streaming.collectAsState()
    val error by engine.error.collectAsState()
    val conversations by engine.conversations.collectAsState()
    val activeId by engine.activeId.collectAsState()
    val listening by voice.isListening.collectAsState()
    val partial by voice.partial.collectAsState()
    val speaking by speaker.speaking.collectAsState()

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var draft by remember { mutableStateOf("") }

    // Follow new turns as they arrive; the engine loads the persisted transcript at startup.
    // (ChatEngine.refresh() is deliberately not called from here: it re-opens the newest
    // conversation and cancels an in-flight reply, which would lose work on a tab switch.)
    LaunchedEffect(messages.size, streaming) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    // A finished dictation lands in the input box so the user can review it before sending.
    LaunchedEffect(voice) {
        voice.heard.collect { utterance ->
            if (utterance.isNotBlank()) draft = utterance
        }
    }

    fun submit() {
        val text = draft.trim()
        if (text.isNotEmpty() && !streaming) {
            engine.send(text)
            draft = ""
        }
    }

    val speakReplies = settings?.speakReplies ?: false
    val modelName = settings?.model.orEmpty()
    val personaLine = settings?.persona?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "HumanPhone",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = when {
                        settings == null -> "Loading settings…"
                        modelName.isBlank() -> "No model selected — open settings"
                        else -> modelName
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (personaLine.isNotEmpty()) {
                    Text(
                        text = personaLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(
                onClick = {
                    scope.launch { settingsStore.update { it.copy(speakReplies = !speakReplies) } }
                },
                enabled = settings != null,
            ) {
                Icon(
                    imageVector = if (speakReplies) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                    contentDescription = if (speakReplies) "Spoken replies on" else "Spoken replies off",
                    tint = if (speaking) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Settings",
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    engine.newConversation()
                    draft = ""
                },
            ) {
                Icon(imageVector = Icons.Filled.Add, contentDescription = "New chat")
            }
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                contentPadding = PaddingValues(end = 12.dp),
            ) {
                items(conversations, key = { it.id }) { conversation ->
                    FilterChip(
                        selected = conversation.id == activeId,
                        onClick = { engine.openConversation(conversation.id) },
                        label = {
                            Text(
                                text = conversation.title.ifBlank { "Untitled chat" },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 160.dp),
                            )
                        },
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (messages.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "No messages yet",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Type below or tap the mic to dictate. Replies are read out loud when the speaker toggle is on.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Send \"${ChatEngine.DO_PREFIX} <task>\" to have the assistant do it on the phone itself.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            items(messages) { turn -> MessageBubble(turn) }
        }

        val visibleError = error?.takeIf { it.isNotBlank() }
        if (visibleError != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
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

        if (streaming || listening || speaking) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (streaming) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = when {
                        listening -> if (partial.isBlank()) "Listening…" else partial
                        streaming -> "Thinking…"
                        else -> "Speaking…"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message HumanPhone") },
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
            )
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(
                onClick = { if (listening) voice.stopListening() else voice.startListening() },
            ) {
                Icon(
                    imageVector = if (listening) Icons.Filled.MicOff else Icons.Filled.Mic,
                    contentDescription = if (listening) "Stop dictating" else "Dictate a message",
                    tint = if (listening) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = { if (streaming) engine.cancel() else submit() },
                enabled = streaming || draft.isNotBlank(),
            ) {
                Icon(
                    imageVector = if (streaming) Icons.Filled.Close else Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (streaming) "Cancel the reply" else "Send",
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(turn: ChatTurn) {
    val isUser = turn.role.equals("user", ignoreCase = true)
    val isSystem = turn.role.equals("system", ignoreCase = true)
    val container = when {
        isUser -> MaterialTheme.colorScheme.primaryContainer
        isSystem -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val onContainer = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (isSystem) container.copy(alpha = 0.6f) else container,
            contentColor = onContainer,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = when {
                        isUser -> "You"
                        isSystem -> "System"
                        else -> "HumanPhone"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isUser) onContainer else MaterialTheme.colorScheme.secondary,
                )
                Spacer(modifier = Modifier.height(2.dp))
                MarkdownText(
                    text = turn.text.ifBlank { "…" },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
