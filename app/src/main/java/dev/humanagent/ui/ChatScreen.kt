@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.humanagent.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.humanagent.HumanPhoneApp
import dev.humanagent.agent.AgentService
import dev.humanagent.chat.ChatEngine
import dev.humanagent.chat.ChatTurn
import dev.humanagent.chat.Conversation
import dev.humanagent.util.ImagePrep
import dev.humanagent.voice.Speaker
import dev.humanagent.voice.VoiceIO
import dev.humanagent.voice.VoiceNotePlayer
import dev.humanagent.voice.VoiceNoteRecorder
import dev.humanagent.voice.voiceNoteDurationSeconds
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The conversation screen: header with the active model and the live-mode switch, conversation
 * switcher, message list with picture and voice-note bubbles, error banner, streaming/listening
 * status, the pending-attachment strip and the input row (text, picture picker, voice note,
 * dictation, send/cancel).
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
    val voiceNotice by voice.notice.collectAsState()
    val speaking by speaker.speaking.collectAsState()
    val liveMode by engine.liveMode.collectAsState()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var draft by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<List<String>>(emptyList()) }
    var notice by remember { mutableStateOf<String?>(null) }
    /** The chat whose delete icon was tapped, held until the user confirms the removal. */
    var pendingDelete by remember { mutableStateOf<Conversation?>(null) }

    // One recorder and one player serve the whole screen; both are released when it leaves
    // composition (VoiceIO.destroy() belongs to the voice slice and is none of our business).
    val recorder = remember { VoiceNoteRecorder(context) }
    val player = remember { VoiceNotePlayer(context) }
    val recording by recorder.recording.collectAsState()
    val elapsedMs by recorder.elapsedMs.collectAsState()
    val playingPath by player.playingPath.collectAsState()

    DisposableEffect(Unit) {
        onDispose {
            // Leaving the screen ends any capture it owned, so live mode gets the microphone back.
            engine.resumeLiveListening(ChatEngine.LivePause.VOICE_NOTE)
            recorder.destroy()
            player.destroy()
        }
    }

    // The photo picker hands back a temporary grant, so the bytes are imported the moment it answers.
    val pickPicture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val imported = withContext(Dispatchers.IO) { importPickedPicture(context, uri) }
                if (imported == null) {
                    notice = "That picture could not be read."
                } else {
                    pending = pending + imported
                    notice = null
                }
            }
        }
    }

    // Asked for the first time only when the user actually reaches for the microphone. The action
    // is stored first, so the callback needs no reference to a function declared further down.
    var afterRecordPermission: (() -> Unit)? by remember { mutableStateOf(null) }
    val recordPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val awaiting = afterRecordPermission
        afterRecordPermission = null
        if (granted) {
            AgentService.refreshForegroundTypes()
            awaiting?.invoke()
        } else {
            notice = "Microphone access is needed to record a voice note."
        }
    }

    // Dictation and live mode both need the microphone, so one prompt serves both.
    var afterMicrophoneGrant: (() -> Unit)? by remember { mutableStateOf(null) }
    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val awaiting = afterMicrophoneGrant
        afterMicrophoneGrant = null
        if (granted) {
            AgentService.refreshForegroundTypes()
            awaiting?.invoke()
        } else {
            notice = "Microphone access is needed to talk to the assistant."
        }
    }

    fun startRecording() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notice = "Microphone access is needed to record a voice note."
            afterRecordPermission = { startRecording() }
            recordPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        // The recogniser and the recorder cannot both hold the microphone, so dictation yields.
        if (listening) voice.stopListening()
        // Live mode keeps the recognition loop running; it waits until the note is done.
        engine.pauseLiveListening(ChatEngine.LivePause.VOICE_NOTE)
        if (recorder.start()) {
            notice = null
        } else {
            notice = "The microphone is busy — nothing was recorded."
            engine.resumeLiveListening(ChatEngine.LivePause.VOICE_NOTE)
        }
    }

    fun sendRecording() {
        val path = recorder.stop()
        if (path == null) {
            notice = "Nothing was recorded — try holding still and speaking."
            return
        }
        if (streaming) {
            // The engine drops a note sent mid-reply, so it is kept in the transcript instead.
            engine.attachVoiceNote(path)
            notice = "Kept the voice note in this chat — the assistant is still answering."
        } else {
            engine.sendVoiceNote(path)
            notice = null
        }
    }

    /** Ends a capture and gives the microphone back to live mode when it is on. */
    fun finishRecording(keep: Boolean) {
        if (keep) {
            sendRecording()
        } else {
            recorder.cancel()
        }
        engine.resumeLiveListening(ChatEngine.LivePause.VOICE_NOTE)
    }

    /** Runs [action] now when the microphone is allowed, otherwise asks for it first. */
    fun withMicrophone(action: () -> Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            action()
        } else {
            afterMicrophoneGrant = action
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /** The dictation button: stop an open session, otherwise open one once the permission is there. */
    fun toggleDictation() {
        if (listening) {
            voice.stopListening()
        } else {
            withMicrophone { voice.startListening() }
        }
    }

    /** The live-mode button: the microphone has to be allowed before a conversation can start. */
    fun toggleLiveMode() {
        if (liveMode) {
            engine.toggleLiveMode()
        } else {
            withMicrophone { engine.toggleLiveMode() }
        }
    }

    // Follow new turns as they arrive; the engine loads the persisted transcript at startup.
    // (ChatEngine.refresh() is deliberately not called from here: it re-opens the newest
    // conversation and cancels an in-flight reply, which would lose work on a tab switch.)
    LaunchedEffect(messages.size, streaming) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    // A finished dictation lands in the input box so the user can review it before sending. In live
    // mode the engine handles the utterance itself, so the box stays empty.
    LaunchedEffect(voice, liveMode) {
        voice.heard.collect { utterance ->
            if (utterance.isNotBlank() && !liveMode) draft = utterance
        }
    }

    fun submit() {
        if (streaming) return
        val text = draft.trim()
        if (pending.isNotEmpty()) {
            engine.sendWithImages(text, pending)
            pending = emptyList()
            draft = ""
            notice = null
            return
        }
        if (text.isNotEmpty()) {
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
            IconButton(onClick = { toggleLiveMode() }) {
                Icon(
                    imageVector = Icons.Filled.RecordVoiceOver,
                    contentDescription = if (liveMode) "Turn live mode off" else "Turn live mode on",
                    tint = if (liveMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

        if (liveMode) {
            Text(
                text = "Live mode — I keep listening while we talk.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    engine.newConversation()
                    draft = ""
                    pending = emptyList()
                    notice = null
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
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "Delete ${conversation.title.ifBlank { "untitled chat" }}",
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { pendingDelete = conversation },
                            )
                        },
                    )
                }
            }
        }

        pendingDelete?.let { target ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text("Delete this chat?") },
                text = {
                    Text(
                        "\"${target.title.ifBlank { "Untitled chat" }}\" and its pictures and voice notes " +
                            "are removed from the phone for good."
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            engine.deleteConversation(target.id)
                            pendingDelete = null
                        },
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) {
                        Text("Keep")
                    }
                },
            )
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
                            text = "Type below, attach a picture or record a voice note. Replies are read out " +
                                "loud when the speaker toggle is on, and live mode keeps the mic open.",
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
            items(messages) { turn ->
                MessageBubble(turn = turn, player = player, playingPath = playingPath)
            }
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

        // A missing permission, an unreadable picture or an empty recording: said inline, once.
        // A refusal from the voice layer (no permission, no recogniser) is shown the same way.
        val visibleNotice = notice?.takeIf { it.isNotBlank() }
            ?: voiceNotice.takeIf { it.isNotBlank() }
        if (visibleNotice != null) {
            Text(
                text = visibleNotice,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        if (pending.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(pending) { path ->
                    PendingPicture(
                        path = path,
                        onRemove = { pending = pending.filterNot { it == path } },
                    )
                }
            }
        }

        if (recording) {
            RecordingBar(
                elapsedMs = elapsedMs,
                onCancel = {
                    finishRecording(keep = false)
                    notice = null
                },
                onSend = { finishRecording(keep = true) },
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        pickPicture.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                ) {
                    Icon(
                        imageVector = Icons.Filled.AttachFile,
                        contentDescription = "Attach a picture",
                    )
                }
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
                IconButton(onClick = { startRecording() }) {
                    Icon(
                        imageVector = Icons.Filled.GraphicEq,
                        contentDescription = "Record a voice note",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Live mode owns the microphone; a separate dictation button would only fight it.
                if (!liveMode) {
                    IconButton(
                        onClick = { toggleDictation() },
                    ) {
                        Icon(
                            imageVector = if (listening) Icons.Filled.MicOff else Icons.Filled.Mic,
                            contentDescription = if (listening) "Stop dictating" else "Dictate a message",
                            tint = if (listening) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(
                    onClick = { if (streaming) engine.cancel() else submit() },
                    enabled = streaming || draft.isNotBlank() || pending.isNotEmpty(),
                ) {
                    Icon(
                        imageVector = if (streaming) Icons.Filled.Close else Icons.AutoMirrored.Filled.Send,
                        contentDescription = if (streaming) "Cancel the reply" else "Send",
                    )
                }
            }
        }
    }
}

/** The composer while a note is being captured: a red bar, a pulsing dot, the clock, discard, send. */
@Composable
private fun RecordingBar(
    elapsedMs: Long,
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.weight(1f),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PulsingDot(color = MaterialTheme.colorScheme.onError)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Recording ${formatElapsed(elapsedMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.width(4.dp))
        IconButton(onClick = onCancel) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Discard the recording",
            )
        }
        IconButton(onClick = onSend) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Send the voice note",
            )
        }
    }
}

/** A breathing dot, the one piece of chrome that says "the microphone is open right now". */
@Composable
private fun PulsingDot(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "recording-dot")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 600), repeatMode = RepeatMode.Reverse),
        label = "recording-dot-alpha",
    )
    Box(
        modifier = modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha)),
    )
}

/** One picture waiting to be sent: a thumbnail with its own remove button. */
@Composable
private fun PendingPicture(path: String, onRemove: () -> Unit) {
    val bitmap = remember(path) { decodePicture(path, maxDimension = 256) }
    Box(modifier = Modifier.size(72.dp)) {
        val picture = bitmap
        if (picture != null) {
            Image(
                bitmap = picture,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp)),
            )
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.BrokenImage,
                        contentDescription = "That picture could not be read",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Remove this picture",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(
    turn: ChatTurn,
    player: VoiceNotePlayer,
    playingPath: String?,
) {
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

                for (path in turn.imagePaths) {
                    val picture = remember(path) { decodePicture(path) }
                    if (picture != null) {
                        Image(
                            bitmap = picture,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .padding(bottom = 4.dp)
                                .widthIn(max = 296.dp)
                                .heightIn(max = 180.dp)
                                .clip(RoundedCornerShape(12.dp)),
                        )
                    } else {
                        Text(
                            text = "Picture unavailable",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                }

                val hasMedia = turn.imagePaths.isNotEmpty() || turn.audioPath != null
                if (turn.text.isNotBlank() || !hasMedia) {
                    MarkdownText(
                        text = turn.text.ifBlank { "…" },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                val audioPath = turn.audioPath
                if (audioPath != null) {
                    VoiceNoteRow(path = audioPath, player = player, playingPath = playingPath)
                }
            }
        }
    }
}

/** A voice note inside a bubble: play or stop, plus how long the recording runs. */
@Composable
private fun VoiceNoteRow(
    path: String,
    player: VoiceNotePlayer,
    playingPath: String?,
) {
    val playing = playingPath == path
    val available = remember(path) {
        val file = File(path)
        file.isFile && file.length() > 0L
    }
    val seconds = remember(path) { voiceNoteDurationSeconds(path) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { if (playing) player.stop() else player.play(path) },
            enabled = available,
        ) {
            Icon(
                imageVector = if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "Stop the voice note" else "Play the voice note",
            )
        }
        Text(
            text = when {
                !available -> "Voice note unavailable"
                seconds != null -> "Voice note · ${seconds}s"
                else -> "Voice note"
            },
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(modifier = Modifier.width(12.dp))
    }
}

/** Recording length as m:ss, the way a voice note reads in a chat app. */
private fun formatElapsed(elapsedMs: Long): String {
    val seconds = (elapsedMs / 1000L).coerceAtLeast(0L)
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

/**
 * Copies a picked picture into app storage: the picker only grants a temporary read on the uri, so
 * the bytes are staged in the cache first and [ImagePrep] then re-encodes them next to the other
 * chat images. The staging file is always deleted.
 */
private fun importPickedPicture(context: Context, uri: Uri): String? {
    val staged = runCatching { File.createTempFile("picked", ".jpg", context.cacheDir) }.getOrNull()
        ?: return null
    try {
        val stream = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
            ?: return null
        stream.use { input ->
            staged.outputStream().use { output -> input.copyTo(output) }
        }
        val destination = File(File(context.filesDir, "chat_images"), "${System.currentTimeMillis()}.jpg")
        return ImagePrep.copyInto(staged.absolutePath, destination)
    } catch (e: Exception) {
        return null
    } finally {
        staged.delete()
    }
}

/** Decodes an attached picture for display, downsampled so a photo does not cost a full Bitmap. */
private fun decodePicture(path: String, maxDimension: Int = 1080): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    if (longest <= 0) return@runCatching null
    val options = BitmapFactory.Options().apply {
        inSampleSize = when {
            longest <= maxDimension -> 1
            longest <= maxDimension * 2 -> 2
            else -> 4
        }
    }
    BitmapFactory.decodeFile(path, options)?.asImageBitmap()
}.getOrNull()
