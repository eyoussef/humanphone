@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.humanagent.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.humanagent.HumanPhoneApp
import dev.humanagent.R
import dev.humanagent.agent.AgentService
import dev.humanagent.chat.Attachment
import dev.humanagent.chat.AttachmentStore
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
    val voiceStatus by voice.status.collectAsState()
    val speaking by speaker.speaking.collectAsState()
    // The agent half runs through the service: its state feeds the in-chat task indicator.
    val agentLoop by AgentService.loop.collectAsState()

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var draft by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<List<Attachment>>(emptyList()) }
    var importing by remember { mutableStateOf(false) }
    var attachError by remember { mutableStateOf<String?>(null) }

    /** Keeps a newly imported file in the outgoing selection, enforcing the per-message image cap. */
    fun holdAttachment(attachment: Attachment) {
        val attached = pending.count { it.kind == AttachmentStore.KIND_IMAGE }
        if (attachment.kind == AttachmentStore.KIND_IMAGE && attached >= AttachmentStore.MAX_IMAGES_PER_MESSAGE) {
            attachError = "You can attach up to ${AttachmentStore.MAX_IMAGES_PER_MESSAGE} photos per message."
            return
        }
        pending = pending + attachment
        attachError = null
    }

    // Copies the picked document into app storage first: the conversation must keep working after
    // the picker's temporary grant is gone.
    fun importAttachment(uri: Uri) {
        scope.launch {
            importing = true
            engine.attachments.import(uri)
                .onSuccess { attachment -> holdAttachment(attachment) }
                .onFailure { failure ->
                    attachError = failure.message?.takeIf(String::isNotBlank) ?: "Could not attach that file."
                }
            importing = false
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) importAttachment(uri)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importAttachment(uri)
    }

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
        if (streaming) return
        if (text.isEmpty() && pending.isEmpty()) return
        engine.send(text, pending)
        draft = ""
        pending = emptyList()
        attachError = null
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
                        settings == null -> stringResource(R.string.loading_settings)
                        modelName.isBlank() -> stringResource(R.string.no_model_selected)
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
                    imageVector = if (speakReplies) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                    contentDescription = if (speakReplies) {
                        stringResource(R.string.spoken_replies_on)
                    } else {
                        stringResource(R.string.spoken_replies_off)
                    },
                    tint = if (speaking) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.open_settings),
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
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.new_chat),
                )
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
                        trailingIcon = {
                            // The ×-sized trash deletes just this conversation; the rest of the
                            // chip keeps switching to it.
                            val conversationTitle = conversation.title.ifBlank { "Untitled chat" }
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = stringResource(
                                    R.string.delete_conversation,
                                    conversationTitle,
                                ),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clickable { engine.deleteConversation(conversation.id) }
                                    .padding(4.dp)
                                    .size(16.dp),
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
                            text = stringResource(R.string.no_messages_yet),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.empty_hint_type),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.empty_hint_do, ChatEngine.DO_PREFIX),
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
                    Text(
                        text = visibleError,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        if (streaming || listening || speaking || voiceStatus.isNotBlank() || agentLoop.running) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (streaming || agentLoop.running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = when {
                        agentLoop.running -> {
                            agentLoop.liveText.ifBlank { stringResource(R.string.status_working_phone) }
                        }
                        voiceStatus.isNotBlank() -> voiceStatus
                        listening -> if (partial.isBlank()) stringResource(R.string.status_listening) else partial
                        streaming -> stringResource(R.string.status_thinking)
                        else -> stringResource(R.string.status_speaking)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (pending.isNotEmpty() || importing || attachError != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            ) {
                if (pending.isNotEmpty()) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        items(pending, key = { it.path }) { attachment ->
                            InputChip(
                                selected = false,
                                onClick = { pending = pending - attachment },
                                label = {
                                    Text(
                                        text = attachment.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 160.dp),
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (attachment.kind == AttachmentStore.KIND_IMAGE) {
                                            Icons.Filled.Image
                                        } else {
                                            Icons.Filled.AttachFile
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                trailingIcon = {
                                    IconButton(
                                        onClick = { pending = pending - attachment },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = "Remove ${attachment.name}",
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
                if (importing) {
                    Text(
                        text = "Preparing attachment…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                attachError?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.weight(1f),
            ) {
                Row(
                    modifier = Modifier.padding(start = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        enabled = !streaming,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Image,
                            contentDescription = stringResource(R.string.attach_photo),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(
                        onClick = { filePicker.launch(arrayOf("*/*")) },
                        enabled = !streaming,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AttachFile,
                            contentDescription = stringResource(R.string.attach_file),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.message_placeholder)) },
                        maxLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            cursorColor = MaterialTheme.colorScheme.primary,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { submit() }),
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = {
                    when {
                        streaming -> engine.cancel()
                        draft.isNotBlank() || pending.isNotEmpty() -> submit()
                        listening -> voice.stopListening()
                        else -> voice.startListening()
                    }
                },
                modifier = Modifier.size(48.dp),
            ) {
                when {
                    streaming -> Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.cancel_reply),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                    draft.isNotBlank() || pending.isNotEmpty() -> Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Send,
                            contentDescription = stringResource(R.string.send_reply),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                    else -> Icon(
                        imageVector = if (listening) Icons.Filled.MicOff else Icons.Filled.Mic,
                        contentDescription = if (listening) {
                            stringResource(R.string.stop_dictating)
                        } else {
                            stringResource(R.string.dictate_message)
                        },
                        tint = if (listening) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val onContainer = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val clipboard = LocalClipboardManager.current

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
            Column(
                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = when {
                            isUser -> "You"
                            isSystem -> "System"
                            else -> "HumanPhone"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isUser) onContainer else MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.weight(1f),
                    )
                    if (turn.text.isNotBlank()) {
                        IconButton(
                            onClick = { clipboard.setText(AnnotatedString(turn.text)) },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = "Copy this message",
                                tint = onContainer.copy(alpha = 0.7f),
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                if (turn.text.isNotBlank()) {
                    // Long-press selects; the copy icon is the one-tap path.
                    SelectionContainer {
                        Text(
                            text = turn.text,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                } else if (turn.attachments.isEmpty()) {
                    Text(
                        text = "…",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                turn.attachments.forEach { attachment ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (attachment.kind == AttachmentStore.KIND_IMAGE) {
                                Icons.Filled.Image
                            } else {
                                Icons.Filled.AttachFile
                            },
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = attachment.name,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
