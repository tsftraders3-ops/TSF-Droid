package com.tsfdroid.ai.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Attachment
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import android.content.Intent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.tsfdroid.ai.core.agent.AgentState
import com.tsfdroid.ai.core.harness.ActivityStep
import com.tsfdroid.ai.core.agent.AutoApprovalPolicy
import com.tsfdroid.ai.core.agent.ChatErrorPrimaryAction
import com.tsfdroid.ai.core.agent.ChatErrorUiState
import com.tsfdroid.ai.core.agent.guidance
import com.tsfdroid.ai.core.agent.primaryAction
import com.tsfdroid.ai.core.agent.title
import com.tsfdroid.ai.core.voice.SpeechRecognitionEngine
import com.tsfdroid.ai.data.models.AutoMode
import com.tsfdroid.ai.data.models.ChatMode
import com.tsfdroid.ai.data.models.ChatMessage
import com.tsfdroid.ai.data.models.effectiveGrantedActions
import com.tsfdroid.ai.data.models.parseMessageAttachments
import com.tsfdroid.ai.ui.components.AgentActivityList
import com.tsfdroid.ai.ui.components.AgentTodoChecklist
import com.tsfdroid.ai.data.models.resolvedAutoMode
import com.tsfdroid.ai.data.repository.ChatSession
import com.tsfdroid.ai.ui.components.ContactPickerCard
import com.tsfdroid.ai.ui.components.RichMessageText
import com.tsfdroid.ai.ui.components.SourceChipsRow
import com.tsfdroid.ai.ui.theme.*
import com.tsfdroid.ai.ui.text.extractUrls
import com.tsfdroid.ai.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val history by viewModel.conversationHistory.collectAsState()
    // Scoped to whichever chat is on screen right now - a task that's actually running
    // in a DIFFERENT chat (safe since the pinned-session fix: it keeps writing there,
    // not wherever the user has navigated to) must never be displayed as if it were
    // happening here. See ChatViewModel.visibleAgentState.
    val visibleAgentState by viewModel.visibleAgentState.collectAsState()
    val chatError by viewModel.chatError.collectAsState()
    // v1.0.5: live reasoning-model thinking trace (what the agent is thinking
    // right now) — rendered under the thinking indicator while it streams.
    val liveThinking by viewModel.liveThinking.collectAsState()
    // Id of whichever chat (if any) has a task actively running, regardless of which
    // chat is currently displayed - drives the chat-picker's "still running" indicator.
    val runningSessionId by viewModel.runningSessionId.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    val llmConfig by viewModel.llmConfig.collectAsState()
    val currentSessionId = sessions.firstOrNull { it.isCurrent }?.id
    val runningElsewhere = runningSessionId != null && runningSessionId != currentSessionId

    val listState = rememberLazyListState()
    var inputQuery by remember { mutableStateOf("") }
    var isListening by remember { mutableStateOf(false) }
    var transcriptionText by remember { mutableStateOf("") }
    var voiceError by remember { mutableStateOf<String?>(null) }
    var showChatMenu by remember { mutableStateOf(false) }
    var sessionPendingDelete by remember { mutableStateOf<ChatSession?>(null) }
    var sessionPendingRename by remember { mutableStateOf<ChatSession?>(null) }
    var renameText by remember { mutableStateOf("") }
    var editingMessageId by remember { mutableStateOf<String?>(null) }
    var textBeforeEdit by remember { mutableStateOf("") }

    // v1.2.0: uploads, Chat/Agent mode, effort selector
    val pendingAttachments by viewModel.pendingAttachments.collectAsState()
    // v1.2.1: the visible-work trace + the live plan (chat-side todo list).
    val liveActivity by viewModel.visibleActivitySteps.collectAsState()
    val livePlan by viewModel.currentPlan.collectAsState()
    val availableEffortLevels by viewModel.availableEffortLevels.collectAsState()
    // v1.3.0: the live ask_user question this chat's task is parked on — drives
    // the dedicated answer surface (accent input bar + option chips).
    val pendingAsk by viewModel.pendingAsk.collectAsState()
    // v1.4.0: the most recent chat export — opens the share sheet for the file
    // the moment it exists, then consumes the slot.
    val exportResult by viewModel.exportResult.collectAsState()
    val chatMode = ChatMode.fromNullable(llmConfig.chatMode)
    var showAttachSheet by remember { mutableStateOf(false) }
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 6)
    ) { uris -> if (uris.isNotEmpty()) viewModel.addPendingAttachments(uris) }
    val filesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> -> if (uris.isNotEmpty()) viewModel.addPendingAttachments(uris) }

    // Merges a piece of dictated text into whatever the user already typed, so review/edit
    // never clobbers text entered before dictation started.
    fun mergeDictatedText(newText: String) {
        if (newText.isBlank()) return
        inputQuery = if (inputQuery.isBlank()) {
            newText
        } else {
            inputQuery.trimEnd() + " " + newText.trimStart()
        }
    }

    // Loads a previously sent user message into the input field for editing, remembering
    // whatever was already typed so cancelling the edit can restore it untouched.
    fun startEditingMessage(message: ChatMessage) {
        if (editingMessageId == null) {
            textBeforeEdit = inputQuery
        }
        editingMessageId = message.id
        inputQuery = message.text
    }

    // Restores the input to whatever it held before the edit started; the conversation
    // itself is never touched until a resend is actually submitted.
    fun cancelEditingMessage() {
        editingMessageId = null
        inputQuery = textBeforeEdit
        textBeforeEdit = ""
    }

    // Single submit path for both a normal send and an edit-resend, wired to both the
    // keyboard "Send" action and the send button below.
    fun submitInput() {
        val text = inputQuery
        // v1.2.0: attachments-only sends are valid — the viewmodel injects the
        // analysis instruction when the text is blank.
        if (text.isBlank() && pendingAttachments.isEmpty()) return
        val editing = editingMessageId
        if (editing != null) {
            viewModel.editAndResend(editing, text, context)
            editingMessageId = null
            textBeforeEdit = ""
        } else {
            viewModel.sendMessage(text, context)
        }
        inputQuery = ""
    }

    // Scroll to bottom on history change. Keyed on currentSessionId too: keying on
    // history.size alone left the scroll position from the PREVIOUS chat in place
    // whenever the newly switched-to chat happened to have the same message count -
    // including currentSessionId forces a re-anchor to the bottom on every chat switch.
    // v1.0.5: the scroll target is the list's true last item (totalItemsCount),
    // not history.size-1 — the plan-approval card renders AFTER the messages,
    // so with a longer history it sat below the fold where the user never saw
    // it and "Approve & Run" was unreachable (the agent appeared to just stop).
    // v1.0.6 loop-21: scrollOffset past the item's start clamps to the list's
    // true end — aligning the item's TOP (previous behavior) still left the
    // tall approval card's BUTTONS below the fold when tall artifact cards
    // preceded it (the cap3 loop-19/20 evidence: card title visible, button
    // never in the a11y tree for 10 minutes).
    // v1.3.0 round-7: the offset clamp had been LOST in a refactor (the code
    // aligned tops again) — run-102 cap22 pass-2 proof: a 514-char researched
    // reply rendered with its SOURCES chips and timestamp composed but fully
    // OCCLUDED under the floating input bar (text bounds ended y=475, input
    // row started y=419; the a11y tree dropped the covered nodes). Pinning
    // the BOTTOM keeps the newest reply's chips/timestamp — and every tall
    // approval card's buttons — inside the visible area, always.
    LaunchedEffect(currentSessionId, history.size, visibleAgentState) {
        if (history.isNotEmpty()) {
            val lastIndex = listState.layoutInfo.totalItemsCount
                .coerceAtLeast(history.size) - 1
            listState.animateScrollToItem(lastIndex, Int.MAX_VALUE)
        }
    }
    // v1.2.1 round-13: the turn's visible-work trace GROWS the thinking
    // bubble in place — reasoning text, then step rows as each tool runs —
    // and none of the keys above change while that happens, so the growing
    // rows sat BELOW THE FOLD, clipped out of the accessibility tree (the
    // cap15 CI evidence: tools executed at 05:06:44 while the on-screen
    // poll never saw a single step row). Re-anchor on every live-trace
    // growth: the user watches the agent work, and the rows stay visible.
    LaunchedEffect(liveActivity.size, liveThinking?.length) {
        if (liveActivity.isNotEmpty() || !liveThinking.isNullOrBlank()) {
            val lastIndex = listState.layoutInfo.totalItemsCount - 1
            if (lastIndex >= 0) listState.animateScrollToItem(lastIndex, Int.MAX_VALUE)
        }
    }

    // v1.4.0 chat export: the file lands in workspace/Exports/, the share
    // sheet opens with it, and a toast confirms what was written. Consumed
    // immediately so exporting the same chat again re-fires the sheet.
    LaunchedEffect(exportResult) {
        val result = exportResult ?: return@LaunchedEffect
        viewModel.consumeExportResult()
        android.widget.Toast.makeText(
            context,
            "Chat exported — ${result.messageCount} messages, " +
                "${android.text.format.Formatter.formatShortFileSize(context, result.byteSize)}",
            android.widget.Toast.LENGTH_LONG
        ).show()
        shareChatExport(context, result.file)
    }

    val speechRecognizer = remember { SpeechRecognitionEngine(context) }
    
    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            isListening = true
            voiceError = null
            transcriptionText = ""
            speechRecognizer.startListening(
                onResult = { text ->
                    isListening = false
                    mergeDictatedText(text)
                    transcriptionText = ""
                },
                onPartialResult = { partial ->
                    transcriptionText = partial
                },
                onError = { err ->
                    isListening = false
                    mergeDictatedText(transcriptionText)
                    transcriptionText = ""
                    voiceError = err
                }
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            speechRecognizer.destroy()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Chat",
                            style = MaterialTheme.typography.headlineSmall,
                            color = TextPrimary,
                            // v1.2.0: the bar hosts the mode + effort + approval
                            // chips on 320dp CI screens — a wrapped title shoves
                            // the plan card's approve button behind the input
                            // overlay. One line, always.
                            maxLines = 1
                        )
                        AgentStatusSubtitle(visibleAgentState, runningElsewhere)
                    }
                },
                actions = {
                    // v1.2.0: CHAT/AGENT mode chip — read-only conversation vs
                    // the full OpenCode-style agent. The label IS the state.
                    OutlinedButton(
                        onClick = { viewModel.cycleChatMode() },
                        border = BorderStroke(
                            1.dp,
                            (if (chatMode == ChatMode.CHAT) AccentCyan else AuroraPrimary).copy(alpha = 0.7f)
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (chatMode == ChatMode.CHAT) AccentCyan else AuroraPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text(
                            text = chatMode.name,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    // v1.2.0: reasoning-effort selector — OpenCode's variant
                    // cycling. Shown ONLY when the active model actually lists
                    // reasoning levels; the tap writes a real reasoning_effort
                    // value onto every request. Not a cosmetic toggle.
                    if (availableEffortLevels.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { viewModel.cycleEffortLevel() },
                            border = BorderStroke(1.dp, TextSecondary.copy(alpha = 0.5f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Speed,
                                contentDescription = "Effort level",
                                modifier = Modifier.size(12.dp),
                                tint = AccentCyan
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = llmConfig.reasoningEffort?.uppercase() ?: "AUTO",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    // Plan approval applies to the agent pipeline only; in CHAT
                    // mode there are no plans, so the chip would be a lie.
                    if (chatMode == ChatMode.AGENT) {
                        val autoMode = llmConfig.resolvedAutoMode()
                        val chipColor = when (autoMode) {
                            AutoMode.OFF -> TextSecondary
                            AutoMode.AUTO -> TextPrimary
                            AutoMode.YOLO -> AccentRed
                        }
                        OutlinedButton(
                            onClick = { viewModel.cycleAutoMode() },
                            border = BorderStroke(1.dp, chipColor.copy(alpha = 0.6f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = chipColor),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text(
                                text = when (autoMode) {
                                    AutoMode.OFF -> "MANUAL"
                                    AutoMode.AUTO -> "AUTO"
                                    AutoMode.YOLO -> "YOLO"
                                },
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.newChat() }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "New chat",
                            tint = TextSecondary
                        )
                    }
                    Box {
                        IconButton(onClick = { showChatMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.Forum,
                                contentDescription = "Chats",
                                tint = TextSecondary
                            )
                        }
                        DropdownMenu(
                            expanded = showChatMenu,
                            onDismissRequest = { showChatMenu = false },
                            modifier = Modifier.background(DarkSurface)
                        ) {
                            // v1.4.0 round 25: the chat ACTIONS lead the menu —
                            // a user with many sessions (or the E2E suite's 8+)
                            // must not scroll a capped-height dropdown past the
                            // whole session list just to export or clear. Same
                            // placement pattern as WhatsApp's menu actions.
                            HorizontalDivider(color = TextSecondary.copy(alpha = 0.2f))
                            // v1.4.0 chat export: the raw-text (JSON) debugging
                            // artifact — every message, the full thinking trace,
                            // every tool call with params/results/durations,
                            // written to workspace/Exports/ and offered to the
                            // share sheet in one tap.
                            DropdownMenuItem(
                                text = { Text("Export chat", color = TextPrimary, fontSize = 13.sp) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Save,
                                        contentDescription = null,
                                        tint = AccentCyan,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                onClick = {
                                    showChatMenu = false
                                    viewModel.exportChat()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Clear chat", color = TextPrimary, fontSize = 13.sp) },
                                onClick = {
                                    showChatMenu = false
                                    viewModel.clearChat()
                                }
                            )
                            if (sessions.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("No chats yet", color = TextSecondary, fontSize = 13.sp) },
                                    onClick = {},
                                    enabled = false
                                )
                            }
                            sessions.forEach { session ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = session.title,
                                                color = if (session.isCurrent) TextPrimary else TextSecondary,
                                                fontWeight = if (session.isCurrent) FontWeight.Bold else FontWeight.Normal,
                                                fontSize = 13.sp,
                                                maxLines = 1
                                            )
                                            // Small "still running" dot: a task can now keep
                                            // executing in a chat the user has switched away
                                            // from, so this is the only place that fact is
                                            // visible once its row scrolls out of the top bar.
                                            if (runningSessionId == session.id) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Box(
                                                    modifier = Modifier
                                                        .size(6.dp)
                                                        .clip(CircleShape)
                                                        .background(AccentCyan)
                                                )
                                            }
                                        }
                                    },
                                    leadingIcon = if (session.isCurrent) {
                                        {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = TextPrimary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    } else null,
                                    trailingIcon = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(
                                                onClick = {
                                                    renameText = session.title
                                                    sessionPendingRename = session
                                                    showChatMenu = false
                                                },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Edit,
                                                    contentDescription = "Rename chat",
                                                    tint = TextSecondary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                            IconButton(
                                                onClick = {
                                                    sessionPendingDelete = session
                                                    showChatMenu = false
                                                },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "Delete chat",
                                                    tint = AccentRed,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        showChatMenu = false
                                        viewModel.switchToSession(session.id)
                                    }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        },
        containerColor = DarkBackground,
        modifier = modifier
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 80.dp)
            ) {
                // Messages List
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    // v1.2.0: 96dp bottom padding — the plan card is often the
                    // last item, and its Approve & Run row must be scrollable
                    // fully ABOVE the floating input overlay (on 320dp screens
                    // the old 16dp let the buttons hide behind it: the E2E
                    // cap1-cap4/cap9/cap11 stalls).
                    contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp)
                ) {
                    // Aurora greeting — the onboarding blob survives into the app as
                    // the greeting avatar above the first exchange.
                    if (history.isEmpty() && visibleAgentState !is AgentState.Thinking) {
                        item(key = "aurora-greeting") {
                            // v1.3.0: greeting follows the wall clock (same
                            // thresholds as HabitRoutineEngine) instead of a
                            // hardcoded "Good morning" at 23:00.
                            val greeting = remember {
                                val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                                when {
                                    hour < 12 -> "Good morning"
                                    hour < 17 -> "Good afternoon"
                                    else -> "Good evening"
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 18.dp, start = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                com.tsfdroid.ai.ui.components.AuroraBlob(size = 64.dp, iconSize = 32.dp)
                                Column {
                                    Text(
                                        text = greeting,
                                        style = MaterialTheme.typography.displayMedium,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = "Agent ready — give me a goal and I'll plan, execute and verify it.",
                                        fontSize = 14.sp,
                                        lineHeight = 20.sp,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }
                    }
                    // v1.3.1 (the thinking-glitch field report): messages
                    // are keyed by their stable id — the streaming reply
                    // grows by REPLACING its row (same id), and Room re-emits
                    // the whole list on every write. With positional keys
                    // every history growth shifted the items below it, so the
                    // keyless ThinkingBubble item was torn down and rebuilt
                    // mid-turn (its infinite dot animation restarting = the
                    // visible flash) and every bubble lost its identity on
                    // every stream delta. Id keys keep item identity across
                    // list re-emissions; Compose then skips bubbles whose
                    // data-class message is unchanged.
                    itemsIndexed(history, key = { _, msg -> msg.id }) { index, msg ->
                        // v1.2.1 round-16: the LIVE step trace renders INSIDE the
                        // streaming reply bubble (above the answer text — the same
                        // grammar as the persisted ACTIVITY section). The old
                        // ThinkingBubble-only placement sat BELOW the growing answer
                        // item in the LazyColumn, so as soon as answer text arrived
                        // the auto-scroll pushed it below the fold and LazyColumn
                        // DISPOSED it — the WEB_SEARCH rows left the a11y tree and
                        // cap15's marker poll never saw them (run 36685166207
                        // evidence). A partially-visible bubble keeps all its
                        // children composed, so rows inside the reply bubble stay
                        // observable for the whole turn.
                        val isStreamingTail = msg.sender == ChatMessage.Sender.AGENT &&
                            index == history.lastIndex &&
                            (visibleAgentState is AgentState.Thinking ||
                                visibleAgentState is AgentState.Speaking)
                        ChatBubble(
                            message = msg,
                            viewModel = viewModel,
                            context = context,
                            onEditRequested = { startEditingMessage(it) },
                            liveActivitySteps = if (isStreamingTail) liveActivity else emptyList()
                        )
                    }
                    
                    // Show a typing/thinking bubble if thinking - scoped to this chat, see
                    // visibleAgentState.
                    if (visibleAgentState is AgentState.Thinking) {
                        // v1.3.1: a STABLE key — a keyless item holds a
                        // positional key, so every history growth (the
                        // streaming reply appending its partial row)
                        // destroyed and recreated the whole bubble mid-think,
                        // restarting the dot animation and flashing the trace.
                        item(key = "thinking-bubble") {
                            ThinkingBubble(liveThinking = liveThinking, steps = liveActivity)
                        }
                    }

                    // v1.2.1: the agent-mode TODO checklist, live in chat while
                    // a plan executes (the Plan tab shows the same plan; this
                    // is the conversation-side mirror, Claude/OpenCode style).
                    if (visibleAgentState is AgentState.ExecutingPlan) {
                        item(key = "agent-todo-checklist") {
                            livePlan?.let { plan ->
                                AgentTodoChecklist(plan = plan)
                            }
                        }
                    }

                    chatError?.let { error ->
                        item(key = "chat-error-${error.requestId}-${error.runId}") {
                            ChatErrorRecoveryCard(
                                error = error,
                                onPrimary = {
                                    when (error.primaryAction()) {
                                        ChatErrorPrimaryAction.RETRY ->
                                            viewModel.retryAfterChatError(context)
                                        ChatErrorPrimaryAction.EDIT_MESSAGE -> {
                                            // Edit the exact message the error is about;
                                            // fall back to the last user message only if
                                            // the requestId no longer resolves to one.
                                            val target = history.firstOrNull {
                                                it.id == error.requestId &&
                                                    it.sender == ChatMessage.Sender.USER
                                            } ?: history.lastOrNull {
                                                it.sender == ChatMessage.Sender.USER
                                            }
                                            target?.let { startEditingMessage(it) }
                                            viewModel.dismissChatError()
                                        }
                                        else -> viewModel.dismissChatError()
                                    }
                                },
                                onDismiss = { viewModel.dismissChatError() }
                            )
                        }
                    }
                }

                // If agent proposed a plan for THIS chat, show a modal prompt to approve or
                // reject. A plan proposed for a different chat must never surface here - the
                // user could approve/reject the wrong chat's plan without realizing it.
                if (visibleAgentState is AgentState.PlanProposed) {
                    val proposedPlan = (visibleAgentState as AgentState.PlanProposed).plan
                    val blocked = if (llmConfig.resolvedAutoMode() == AutoMode.AUTO) {
                        AutoApprovalPolicy.blockedActions(
                            llmConfig.effectiveGrantedActions().keys, proposedPlan.steps
                        )
                    } else emptyList()
                    ProposedPlanPrompt(
                        planId = proposedPlan.planId,
                        goal = proposedPlan.goal,
                        stepsCount = proposedPlan.estimatedSteps,
                        blockedActions = blocked,
                        grantableActions = blocked.filter { AutoApprovalPolicy.isGrantable(it) }.toSet(),
                        onApprove = { grants -> viewModel.approvePlan(context, grants) },
                        onReject = { viewModel.rejectPlan() }
                    )
                }
            }

            // Bottom Input Section with Orb overlay
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, DarkBackground),
                            startY = 0f,
                            endY = 50f
                        )
                    )
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // v1.3.0 ANSWER SURFACE — when this chat's agent task is
                    // parked on an ask_user question, the input bar becomes
                    // the answer box: the question is repeated as a labeled
                    // strip with its tappable option chips directly above the
                    // field, and the field itself gets the accent treatment so
                    // there is never any doubt WHERE the answer goes.
                    pendingAsk?.let { ask ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 4.dp, bottom = 8.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(AccentCyan.copy(alpha = 0.10f))
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.HelpOutline,
                                    contentDescription = null,
                                    tint = AccentCyan,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "ANSWER NEEDED",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AccentCyan,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = ask.question,
                                fontSize = 13.sp,
                                color = TextPrimary,
                                lineHeight = 17.sp
                            )
                            if (ask.options.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    ask.options.forEach { option ->
                                        Text(
                                            text = option,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = AccentCyan,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(14.dp))
                                                .background(AccentCyan.copy(alpha = 0.16f))
                                                .clickable {
                                                    viewModel.sendMessage(option, context)
                                                }
                                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    // v1.2.0: pending upload strip — removable chips for
                    // everything that will be sent with the next message.
                    if (pendingAttachments.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 4.dp, bottom = 8.dp)
                                .horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            pendingAttachments.forEachIndexed { index, pending ->
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(AuroraSurfaceHigh)
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (pending.mime.startsWith("image/")) {
                                            Icons.Filled.Image
                                        } else {
                                            Icons.Filled.Description
                                        },
                                        contentDescription = null,
                                        tint = AccentCyan,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = pending.name.take(24),
                                        fontSize = 11.sp,
                                        color = TextPrimary,
                                        maxLines = 1
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove attachment ${index + 1}",
                                        tint = TextSecondary,
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clickable { viewModel.removePendingAttachment(index) }
                                    )
                                }
                            }
                        }
                    }
                    if (editingMessageId != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 4.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = null,
                                tint = AccentCyan,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Editing message",
                                fontSize = 11.sp,
                                color = AccentCyan,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel edit",
                                tint = TextSecondary,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { cancelEditingMessage() }
                            )
                        }
                    }
                    if (voiceError != null) {
                        Text(
                            text = voiceError.orEmpty(),
                            fontSize = 11.sp,
                            color = AccentRed,
                            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Floating Orb for Speech
                        FloatingOrb(
                            isListening = isListening,
                            agentState = visibleAgentState,
                            onClick = {
                                if (isListening) {
                                    // True cancel: no final result will be delivered for this
                                    // session, so whatever partial transcript we already have is
                                    // handed back to the input field instead of being lost.
                                    speechRecognizer.cancel()
                                    isListening = false
                                    mergeDictatedText(transcriptionText)
                                    transcriptionText = ""
                                } else {
                                    val audioPerm = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    )
                                    if (audioPerm == PackageManager.PERMISSION_GRANTED) {
                                        isListening = true
                                        voiceError = null
                                        transcriptionText = ""
                                        speechRecognizer.startListening(
                                            onResult = { text ->
                                                isListening = false
                                                mergeDictatedText(text)
                                                transcriptionText = ""
                                            },
                                            onPartialResult = { partial ->
                                                transcriptionText = partial
                                            },
                                            onError = { err ->
                                                isListening = false
                                                mergeDictatedText(transcriptionText)
                                                transcriptionText = ""
                                                voiceError = err
                                            }
                                        )
                                    } else {
                                        recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                            }
                        )

                        Spacer(modifier = Modifier.width(4.dp))

                        // v1.2.0: attach — images, documents, PDFs, video.
                        IconButton(
                            onClick = { showAttachSheet = true },
                            modifier = Modifier.size(42.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Attachment,
                                contentDescription = "Attach file",
                                tint = TextSecondary
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Text Input Field / Voice Waveform Area — Aurora chatfield:
                        // r-hero 30px, tonal surface-high fill, no border.
                        // v1.3.0: while an ask_user question is pending, the
                        // SAME field is the answer box — accent border + an
                        // "answer the question" placeholder make the contract
                        // visible; the send path is unchanged (processQuery
                        // routes it to the parked question).
                        val answeringAsk = pendingAsk != null
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 54.dp, max = 120.dp)
                                .clip(RoundedCornerShape(30.dp))
                                .background(AuroraSurfaceHigh)
                                .then(
                                    if (answeringAsk) {
                                        Modifier.border(
                                            1.5.dp,
                                            AccentCyan.copy(alpha = 0.65f),
                                            RoundedCornerShape(30.dp)
                                        )
                                    } else {
                                        Modifier
                                    }
                                )
                                .padding(horizontal = 18.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (isListening) {
                                VoiceWaveform(
                                    text = transcriptionText,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .verticalScroll(rememberScrollState())
                                        .padding(vertical = 12.dp)
                                )
                            } else {
                                TextField(
                                    value = inputQuery,
                                    onValueChange = { inputQuery = it; voiceError = null },
                                    placeholder = {
                                        Text(
                                            text = if (answeringAsk) {
                                                "Type your answer — the agent is waiting…"
                                            } else {
                                                "Ask TSF Droid to run an autonomous task..."
                                            },
                                            color = if (answeringAsk) AccentCyan else TextSecondary,
                                            fontSize = 14.sp
                                        )
                                    },
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        disabledContainerColor = Color.Transparent,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                        focusedTextColor = TextPrimary,
                                        unfocusedTextColor = TextPrimary
                                    ),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                    keyboardActions = KeyboardActions(onSend = { submitInput() }),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        AnimatedVisibility(
                            visible = !isListening && (inputQuery.isNotBlank() || pendingAttachments.isNotEmpty()),
                            enter = fadeIn(animationSpec = tween(150)) + scaleIn(initialScale = 0.8f),
                            exit = fadeOut(animationSpec = tween(150)) + scaleOut(targetScale = 0.8f)
                        ) {
                            Row {
                                Spacer(modifier = Modifier.width(8.dp))
                                IconButton(
                                    onClick = { submitInput() },
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(RoundedCornerShape(30.dp))
                                        .background(AuroraPrimary)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Send,
                                        contentDescription = "Send",
                                        tint = AuroraOnPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // v1.2.0: attach source picker — gallery images or any document (text,
    // code, PDF, video). The processor turns each into model-ready content.
    if (showAttachSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAttachSheet = false },
            containerColor = DarkSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Attach for the model to see",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Images and video frames are routed to a vision-capable model automatically. Text files are read inline; PDFs become page images.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))
                ListItem(
                    headlineContent = { Text("Photos", color = TextPrimary) },
                    supportingContent = { Text("Up to 6 images", color = TextSecondary, fontSize = 12.sp) },
                    leadingContent = {
                        Icon(Icons.Filled.Image, contentDescription = null, tint = AccentCyan)
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            showAttachSheet = false
                            galleryLauncher.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        }
                )
                ListItem(
                    headlineContent = { Text("Files", color = TextPrimary) },
                    supportingContent = {
                        Text("Text, code, PDF, video", color = TextSecondary, fontSize = 12.sp)
                    },
                    leadingContent = {
                        Icon(Icons.Filled.Description, contentDescription = null, tint = AccentCyan)
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            showAttachSheet = false
                            filesLauncher.launch(arrayOf("*/*"))
                        }
                )
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }

    sessionPendingDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { sessionPendingDelete = null },
            containerColor = DarkSurface,
            title = { Text("Delete chat?", color = TextPrimary) },
            text = {
                Text(
                    "\"${session.title}\" and its messages will be permanently deleted.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteChat(session.id)
                    sessionPendingDelete = null
                }) {
                    Text("Delete", color = AccentRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionPendingDelete = null }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

    sessionPendingRename?.let { session ->
        AlertDialog(
            onDismissRequest = { sessionPendingRename = null },
            containerColor = DarkSurface,
            title = { Text("Rename chat", color = TextPrimary) },
            text = {
                TextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = AccentCyan,
                        unfocusedIndicatorColor = BorderColor,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameSession(session.id, renameText)
                    sessionPendingRename = null
                }) {
                    Text("Save", color = TextPrimary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionPendingRename = null }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }
}

@Composable
fun AgentStatusSubtitle(state: AgentState, runningElsewhere: Boolean = false) {
    // runningElsewhere means [state] has already been forced to Idle because the real
    // activity belongs to a different chat (see ChatViewModel.visibleAgentState) - say
    // so explicitly instead of showing a plain "Online & Ready" that would hide the
    // fact that a task is still going in the background.
    val text = if (runningElsewhere) {
        "Online & Ready · Task running in another chat"
    } else {
        when (state) {
            is AgentState.Idle -> "Online & Ready"
            is AgentState.Listening -> "Listening to voice input..."
            is AgentState.Thinking -> "Analyzing intent & planning..."
            is AgentState.PlanProposed -> "Requires Plan Approval"
            is AgentState.ExecutingPlan -> "Executing: ${state.currentStepDesc}"
            is AgentState.Speaking -> "Speaking: ${state.text.take(30)}..."
            is AgentState.Error -> "Execution Error"
        }
    }

    val color = if (runningElsewhere) {
        AccentPurple
    } else {
        when (state) {
            is AgentState.Idle -> TextSecondary
            is AgentState.Listening -> AccentRed
            is AgentState.Thinking -> AccentPurple
            is AgentState.PlanProposed -> AccentCyan
            is AgentState.ExecutingPlan -> AccentCyan
            is AgentState.Speaking -> AccentCyan
            is AgentState.Error -> AccentRed
        }
    }

    Text(
        text = text,
        fontSize = 11.sp,
        color = color,
        fontFamily = FontFamily.SansSerif,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
fun ChatBubble(
    message: ChatMessage,
    viewModel: ChatViewModel? = null,
    context: android.content.Context? = null,
    onEditRequested: ((ChatMessage) -> Unit)? = null,
    liveActivitySteps: List<ActivityStep> = emptyList()
) {
    val isAgent = message.sender == ChatMessage.Sender.AGENT
    val alignment = if (isAgent) Alignment.Start else Alignment.End
    val colors = AppTheme.colors
    // Aurora bubbles: agent = surface-high with the flat corner pointing at the
    // speaker (bottom-left); user = primary fill with the flat corner bottom-right.
    val bubbleColor = if (isAgent) colors.surfaceHigh else colors.primary
    val bubbleTextColor = if (isAgent) colors.textPrimary else colors.onPrimary
    val stampColor = if (isAgent) colors.textSecondary else colors.onPrimary.copy(alpha = 0.72f)
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    // If this is a contact picker message, render the ContactPickerCard instead
    if (isAgent && message.contactPickerData != null) {
        val matches: List<Map<String, String>> = try {
            Json { ignoreUnknownKeys = true }
                .decodeFromString<List<Map<String, String>>>(message.contactPickerData)
        } catch (_: Exception) {
            emptyList()
        }

        if (matches.isNotEmpty()) {
            // Extract query from text ("Which 'dad' do you mean?" ? "dad")
            val query = Regex("Which '(.*?)'").find(message.text)?.groupValues?.getOrNull(1) ?: "contact"

            ContactPickerCard(
                query = query,
                matches = matches,
                onContactSelected = { selected ->
                    val index = matches.indexOf(selected) + 1
                    if (viewModel != null && context != null) {
                        viewModel.sendMessage(index.toString(), context)
                    }
                }
            )
            return
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isAgent) Arrangement.Start else Arrangement.End
        ) {
            // v1.4.0 OPEN REPLIES (the 2026-10-03 field feedback): agent
            // answers render ChatGPT/Claude/Gemini-style — full-width, on the
            // canvas, no bubble box. Only the USER's messages keep the
            // rounded bubble (right-aligned, primary fill). The old boxed
            // agent reply read like two people texting each other in an
            // inbox; an assistant answers in the open.
            Column(
                modifier = if (isAgent) {
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                } else {
                    Modifier
                        .widthIn(max = 340.dp)
                        .clip(
                            RoundedCornerShape(
                                topStart = 30.dp,
                                topEnd = 30.dp,
                                bottomStart = 30.dp,
                                bottomEnd = 6.dp
                            )
                        )
                        .background(bubbleColor)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                }
            ) {
                if (isAgent && message.modelBadge != null) {
                    val displayName = when (message.modelBadge) {
                        "Gemma 4 (On-device)" -> "ON-DEVICE (AI CORE)"
                        "On-Device AI" -> "ON-DEVICE AI"
                        "LiteRT-LM (On-device)" -> "ON-DEVICE (LITERT)"
                        else -> message.modelBadge.uppercase(Locale.getDefault())
                    }
                    Text(
                        text = displayName,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentCyan,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }

                // v1.2.0: user uploads render INSIDE the bubble — uploaded
                // images as thumbnails, text files as chips — so the user can
                // see exactly what the model was given (Claude/ChatGPT-style).
                if (!isAgent) {
                    val uploads = message.attachments()
                    val uploadedImages = message.allImages()
                    if (uploadedImages.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(bottom = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            uploadedImages.forEach { base64 ->
                                val decoded = remember(base64.hashCode()) {
                                    runCatching {
                                        android.util.Base64.decode(
                                            base64, android.util.Base64.DEFAULT
                                        )
                                    }.getOrNull()
                                }
                                if (decoded != null) {
                                    val bitmap = remember(decoded) {
                                        BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
                                    }
                                    if (bitmap != null) {
                                        Image(
                                            bitmap = bitmap.asImageBitmap(),
                                            contentDescription = "Uploaded image",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(96.dp)
                                                .clip(RoundedCornerShape(14.dp))
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (uploads?.files?.isNotEmpty() == true) {
                        Column(modifier = Modifier.padding(bottom = 6.dp)) {
                            uploads.files.forEach { file ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Filled.Description,
                                        contentDescription = null,
                                        tint = bubbleTextColor,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = file.name,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = bubbleTextColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                // v1.0.5: reasoning-model thinking trace — collapsible section
                // above the answer so the user can inspect WHAT the agent was
                // thinking. Auto-expanded while only thinking has arrived
                // (still streaming), collapsed once the answer is present.
                // v1.0.6: untruncated — the trace is scrollable to 400dp so
                // the full reasoning stays inspectable.
                if (isAgent && !message.thinkingText.isNullOrBlank()) {
                    var thinkingExpanded by remember(message.id) {
                        mutableStateOf(message.text.isBlank())
                    }
                    // v1.3.0: Claude-style header — when the turn's reasoning
                    // phase was measured, the collapsed section reads
                    // "THOUGHT FOR 12s" instead of a bare "THINKING" label.
                    // The remember key includes stepsJson presence: the live
                    // bubble first renders WITHOUT steps, and the persisted
                    // trace (carrying the duration) lands on the SAME message
                    // id later — a pure id key would cache the null forever.
                    val thoughtForLabel = remember(message.id, message.stepsJson != null) {
                        message.activitySteps()
                            .firstOrNull { it.kind == ActivityStep.KIND_THINKING }
                            ?.label
                            ?.uppercase(Locale.getDefault())
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { thinkingExpanded = !thinkingExpanded }
                            .padding(bottom = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = if (thinkingExpanded) "Collapse thinking" else "Expand thinking",
                            tint = AccentPurple,
                            modifier = Modifier
                                .size(14.dp)
                                .graphicsLayer {
                                    rotationZ = if (thinkingExpanded) 0f else -90f
                                }
                        )
                        Text(
                            text = thoughtForLabel
                                ?: if (message.text.isBlank()) "THINKING" else "THOUGHT",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccentPurple,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                    if (thinkingExpanded) {
                        Text(
                            text = message.thinkingText!!,
                            fontSize = 11.sp,
                            color = TextSecondary,
                            lineHeight = 15.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 400.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(bottom = 4.dp)
                        )
                    }
                }

                // v1.2.1 round-16: the LIVE step trace of the turn that is
                // still producing THIS reply. Same placement/grammar as the
                // persisted section below — Claude/OpenCode show the work
                // above the answer — and it is what makes the trace visible
                // BEFORE the final save lands (the persisted one can only
                // render once stepsJson is written).
                if (isAgent && message.stepsJson == null && liveActivitySteps.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)
                    ) {
                        Text(
                            text = "ACTIVITY (${liveActivitySteps.size})",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccentCyan,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                    AgentActivityList(
                        steps = liveActivitySteps,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }

                // v1.2.1: the persisted ACTIVITY trace — the visible steps the
                // agent took for THIS reply (tool calls, continuations,
                // compactions, plan steps). Same collapsible grammar as
                // THINKING; auto-expanded while the reply body is still empty.
                if (isAgent && message.stepsJson != null) {
                    // v1.3.0 round-9: stepsJson joins the remember key. The row
                    // first composes DURING streaming (persistReply writes it
                    // before any Content delta lands), i.e. with stepsJson ==
                    // null — a pure id key then caches the empty decode forever,
                    // and when the final save REPLACEs the row with the step
                    // trace (all three harness handoff saves), the ACTIVITY
                    // header never renders even though the DB row carries it
                    // (run-105 cap15 evidence: steps=2 jsonLen=1746 saved, no
                    // header on the bubble). Same class as the thoughtForLabel
                    // key fixed in round 8.
                    val steps = remember(message.id, message.stepsJson) {
                        message.activitySteps()
                    }
                    if (steps.isNotEmpty()) {
                        var activityExpanded by remember(message.id) {
                            mutableStateOf(message.text.isBlank())
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { activityExpanded = !activityExpanded }
                                .padding(bottom = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = if (activityExpanded) "Collapse activity" else "Expand activity",
                                tint = AccentCyan,
                                modifier = Modifier
                                    .size(14.dp)
                                    .graphicsLayer {
                                        rotationZ = if (activityExpanded) 0f else -90f
                                    }
                            )
                            Text(
                                text = "ACTIVITY (${steps.size})",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = AccentCyan,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                        if (activityExpanded) {
                            AgentActivityList(
                                steps = steps,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                        }
                    }
                }

                // v1.3.0 (Phase 14, Wave B): agent replies render through the
                // markdown-lite rich renderer — headings, bullets, code fences,
                // tappable links — plus a numbered SOURCES chip row when the
                // reply cites URLs. User bubbles stay plain text.
                if (isAgent) {
                    RichMessageText(
                        text = message.text,
                        baseColor = bubbleTextColor
                    )
                    val sourceUrls = remember(message.id, message.text) {
                        extractUrls(message.text)
                    }
                    if (sourceUrls.isNotEmpty()) {
                        SourceChipsRow(
                            urls = sourceUrls,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                } else {
                    Text(
                        text = message.text,
                        fontSize = 14.sp,
                        color = bubbleTextColor,
                        lineHeight = 21.sp
                    )
                }

                // v1.0.6 → v1.4.0: file attachment card — agent-created
                // artifacts are REAL files the user can open/share directly from
                // the chat. v1.4.0 moved it BELOW the reply text + sources:
                // ChatGPT/Claude/Gemini deliver the created file at the END of
                // the answer, never above it.
                if (isAgent && message.attachmentJson != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    FileAttachmentCard(attachmentJson = message.attachmentJson!!)
                }

                // v1.3.0 ask_user bubble: tappable option chips under the
                // question. While the ask is live these answer the parked
                // question (the answer-mode strip above the input mirrors
                // them); afterwards they still send the option as a plain
                // message — the model sees the whole history either way.
                if (isAgent) {
                    message.askOptions()?.let { ask ->
                        if (ask.options.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                ask.options.forEach { option ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(AccentCyan.copy(alpha = 0.12f))
                                            .clickable {
                                                if (viewModel != null && context != null) {
                                                    viewModel.sendMessage(option, context)
                                                }
                                            }
                                            .padding(horizontal = 12.dp, vertical = 8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Check,
                                            contentDescription = null,
                                            tint = AccentCyan,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = option,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Edit affordance: user messages only, never on agent replies or the
                    // contact-picker card (which is always an agent message, so it's
                    // already excluded by the isAgent check above never reaching here).
                    if (!isAgent && onEditRequested != null) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit message",
                            tint = TextSecondary,
                            modifier = Modifier
                                .size(13.dp)
                                .clickable { onEditRequested(message) }
                        )
                    }
                    Text(
                        text = timeFormat.format(Date(message.timestamp)),
                        fontSize = 9.sp,
                        color = stampColor
                    )
                }
            }
        }
    }
}

/**
 * v1.0.6: file attachment card for agent-created artifacts. Renders the
 * real file name, type icon and size with Open + Share actions backed by
 * FileProvider — created files are first-class objects in the chat, never
 * just a path inside a text bubble.
 */
@Composable
fun FileAttachmentCard(attachmentJson: String) {
    val context = LocalContext.current
    val attachment: org.json.JSONObject = try {
        org.json.JSONObject(attachmentJson)
    } catch (_: Exception) {
        return
    }
    val name = attachment.optString("name", "file")
    val mime = attachment.optString("mime", "application/octet-stream")
    val size = attachment.optLong("size", 0L)
    val sizeLabel = when {
        size >= 1_048_576 -> String.format(java.util.Locale.US, "%.1f MB", size / 1_048_576.0)
        size >= 1024 -> String.format(java.util.Locale.US, "%.1f KB", size / 1024.0)
        else -> "$size B"
    }
    val (typeLabel, typeColor) = when {
        name.endsWith(".pdf", true) -> "PDF" to AccentPurple
        name.endsWith(".html", true) || name.endsWith(".htm", true) -> "HTML" to AccentCyan
        name.endsWith(".json", true) -> "JSON" to AccentCyan
        name.endsWith(".csv", true) -> "CSV" to AccentNeonGreen
        else -> "FILE" to TextSecondary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, BorderColor, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(typeColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = typeLabel.take(4),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = typeColor,
                fontFamily = FontFamily.Monospace
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp)
        ) {
            Text(
                text = name,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = sizeLabel,
                fontSize = 10.sp,
                color = TextSecondary
            )
        }
        Text(
            text = "OPEN",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = AccentCyan,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    openArtifact(context, attachment, mime)
                }
                .padding(horizontal = 10.dp, vertical = 8.dp)
        )
        Text(
            text = "SHARE",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = AccentCyan,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    shareArtifact(context, attachment, mime)
                }
                .padding(horizontal = 10.dp, vertical = 8.dp)
        )
    }
}

/**
 * Resolves an artifact to a grantable content URI. Files in the app's own
 * roots go straight through FileProvider; anything else (path moved, root
 * mismatch) is copied into cache/exports (a declared root) first, so a card
 * can never be un-openable just because the recorded path drifted. Returns
 * null (with a reason toast) when the file is gone or unreadable.
 */
private fun artifactUri(context: android.content.Context, path: String): android.net.Uri? {
    val file = java.io.File(path)
    if (!file.exists() || file.length() == 0L) {
        android.widget.Toast.makeText(
            context,
            "That file is no longer in the workspace",
            android.widget.Toast.LENGTH_SHORT
        ).show()
        return null
    }
    return try {
        androidx.core.content.FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file
        )
    } catch (_: Exception) {
        // Outside every declared root — copy into the cache export root.
        try {
            val exports = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
            val copy = java.io.File(exports, file.name)
            file.inputStream().use { input ->
                copy.outputStream().use { output -> input.copyTo(output) }
            }
            androidx.core.content.FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                copy
            )
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                context,
                "Couldn't access the file: ${e.localizedMessage}",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            null
        }
    }
}

private fun openArtifact(context: android.content.Context, attachment: org.json.JSONObject, mime: String) {
    val uri = artifactUri(context, attachment.optString("path")) ?: return
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        android.widget.Toast.makeText(
            context,
            "No app on this device can open $mime files",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
}

private fun shareArtifact(context: android.content.Context, attachment: org.json.JSONObject, mime: String) {
    val uri = artifactUri(context, attachment.optString("path")) ?: return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(Intent.createChooser(intent, "Share file"))
    } catch (e: Exception) {
        android.widget.Toast.makeText(
            context,
            "Couldn't open the share sheet: ${e.localizedMessage}",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
}

/**
 * v1.4.0 chat export: shares the raw-text (JSON) export file through the
 * system share sheet — same FileProvider grant path as agent artifacts, so
 * the file is handable to any app (email, drive, chat) in one tap. Never
 * `EXTRA_TEXT` (Binder limit); always `EXTRA_STREAM` with the real file.
 */
private fun shareChatExport(context: android.content.Context, file: java.io.File) {
    val uri = try {
        androidx.core.content.FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file
        )
    } catch (e: Exception) {
        android.widget.Toast.makeText(
            context,
            "Export saved at ${file.absolutePath}",
            android.widget.Toast.LENGTH_LONG
        ).show()
        return
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(Intent.createChooser(intent, "Share chat export"))
    } catch (e: Exception) {
        android.widget.Toast.makeText(
            context,
            "Export saved at ${file.absolutePath}",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }
}

@Composable
fun ThinkingBubble(
    liveThinking: String? = null,
    steps: List<com.tsfdroid.ai.core.harness.ActivityStep> = emptyList()
) {
    val transition = rememberInfiniteTransition(label = "thinking")
    val dot1 by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "dot1"
    )
    val dot2 by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600, delayMillis = 200, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "dot2"
    )
    val dot3 by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600, delayMillis = 400, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "dot3"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp))
                .background(CardBackground)
                .border(1.dp, BorderColor, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(TextPrimary.copy(alpha = dot1)))
                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(TextPrimary.copy(alpha = dot2)))
                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(TextPrimary.copy(alpha = dot3)))
            }
            // v1.0.5: while a reasoning model streams its thinking, show the
            // tail of the live trace under the dots — the user watches what
            // the agent is thinking instead of an indeterminate spinner.
            // v1.0.6: larger surface (1200 chars / 10 lines) and tool-loop
            // status lines surface here too.
            liveThinking?.takeIf { it.isNotBlank() }?.let { trace ->
                Text(
                    text = trace.takeLast(1200),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondary,
                    lineHeight = 14.sp,
                    maxLines = 10,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            // v1.2.1: the live visible-step trace — every tool call, output
            // continuation, compaction, and plan step as it happens.
            if (steps.isNotEmpty()) {
                AgentActivityList(
                    steps = steps,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
fun ProposedPlanPrompt(
    planId: String,
    goal: String,
    stepsCount: Int,
    blockedActions: List<String> = emptyList(),
    grantableActions: Set<String> = emptySet(),
    onApprove: (Set<String>) -> Unit,
    onReject: () -> Unit
) {
    // Keyed on both: `planId` because a new plan must not inherit the previous
    // plan's ticked grants even when the two happen to block the same actions,
    // and `blockedActions` because the checkboxes are drawn from that list, so a
    // change to it would otherwise leave ticks referring to rows that are gone.
    var checkedGrants by remember(planId, blockedActions) { mutableStateOf(setOf<String>()) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .border(1.dp, AccentCyan, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Plan Proposed",
                    tint = AccentCyan,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "AUTONOMOUS PLAN PROPOSED",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = AccentCyan
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Goal: \"$goal\"",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "TSF Droid has formulated a sequence of $stepsCount steps to complete this goal. Review the steps in the PLAN tab or approve below to execute.",
                fontSize = 12.sp,
                color = TextSecondary
            )
            if (blockedActions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "BLOCKED AUTO-RUN — these steps aren't in your allowlist:",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = AccentRed
                )
                Spacer(modifier = Modifier.height(4.dp))
                blockedActions.forEach { action ->
                    if (action in grantableActions) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = action in checkedGrants,
                                onCheckedChange = { checked ->
                                    checkedGrants = if (checked) checkedGrants + action else checkedGrants - action
                                },
                                colors = CheckboxDefaults.colors(checkedColor = AccentCyan)
                            )
                            Text(
                                text = "Always allow $action",
                                fontSize = 13.sp,
                                color = TextPrimary
                            )
                        }
                    } else {
                        Text(
                            text = "• $action (always asks)",
                            fontSize = 13.sp,
                            color = TextSecondary,
                            modifier = Modifier.padding(start = 12.dp, top = 4.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick = onReject,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed),
                    border = BorderStroke(1.dp, AccentRed.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Reject", fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = { onApprove(checkedGrants) },
                    colors = ButtonDefaults.buttonColors(containerColor = TextPrimary, contentColor = DarkBackground),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Approve & Run", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun FloatingOrb(
    isListening: Boolean,
    agentState: AgentState,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val colorPulse by animateColorAsState(
        targetValue = when {
            isListening -> AccentRed
            agentState is AgentState.Thinking -> AccentPurple
            agentState is AgentState.ExecutingPlan -> AccentCyan
            agentState is AgentState.Speaking -> AccentCyan
            else -> TextPrimary.copy(alpha = 0.25f)
        },
        animationSpec = tween(500),
        label = "color"
    )

    val shadowSize = if (isListening || agentState !is AgentState.Idle) pulseScale else 1f

    Box(
        modifier = Modifier
            .size(56.dp)
            .scale(shadowSize)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(colorPulse, Color.Transparent),
                    radius = 120f
                )
            )
            .clickable { onClick() }
            .padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(colorPulse, colorPulse.copy(alpha = 0.6f))
                    )
                )
                .border(2.dp, TextPrimary.copy(alpha = 0.2f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(DarkBackground)
            )
        }
    }
}

@Composable
fun VoiceWaveform(text: String, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    val heightScale1 by infiniteTransition.animateFloat(
        initialValue = 4f,
        targetValue = 32f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "h1"
    )
    val heightScale2 by infiniteTransition.animateFloat(
        initialValue = 6f,
        targetValue = 24f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, delayMillis = 100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "h2"
    )
    val heightScale3 by infiniteTransition.animateFloat(
        initialValue = 8f,
        targetValue = 40f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 50, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "h3"
    )

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Top
    ) {
        Row(
            modifier = Modifier.width(60.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.width(4.dp).height(heightScale1.dp).clip(CircleShape).background(AccentRed))
            Box(modifier = Modifier.width(4.dp).height(heightScale2.dp).clip(CircleShape).background(AccentRed))
            Box(modifier = Modifier.width(4.dp).height(heightScale3.dp).clip(CircleShape).background(AccentRed))
            Box(modifier = Modifier.width(4.dp).height(heightScale2.dp).clip(CircleShape).background(AccentRed))
            Box(modifier = Modifier.width(4.dp).height(heightScale1.dp).clip(CircleShape).background(AccentRed))
        }
        Spacer(modifier = Modifier.width(8.dp))
        // No maxLines cap - long dictation wraps across multiple lines and the container
        // (see the input Box in ChatScreen) scrolls once it exceeds its bounded max height.
        Text(
            text = text,
            fontSize = 13.sp,
            color = TextPrimary,
            fontFamily = FontFamily.SansSerif,
            lineHeight = 18.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ChatErrorRecoveryCard(
    error: ChatErrorUiState,
    onPrimary: () -> Unit,
    onDismiss: () -> Unit
) {
    var detailsExpanded by remember { mutableStateOf(false) }
    // Countdown for rate-limited errors: while the provider's retry-after window is
    // open, the Retry button is disabled and the remaining seconds tick down here.
    val phase = error.phase
    var waitSecondsLeft by remember(phase) {
        mutableLongStateOf(
            if (phase is ChatErrorUiState.Phase.WaitingUntil) {
                ((phase.epochMillis - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
            } else {
                0L
            }
        )
    }
    if (phase is ChatErrorUiState.Phase.WaitingUntil) {
        LaunchedEffect(phase) {
            while (true) {
                val remainingMillis = phase.epochMillis - System.currentTimeMillis()
                waitSecondsLeft = (remainingMillis / 1000L).coerceAtLeast(0L)
                if (remainingMillis <= 0L) break
                delay(1000L)
            }
        }
    }
    val retryHeld = phase is ChatErrorUiState.Phase.Retrying ||
        (phase is ChatErrorUiState.Phase.WaitingUntil && waitSecondsLeft > 0L)
    val actionLabel = when (error.primaryAction()) {
        ChatErrorPrimaryAction.OPEN_SETTINGS -> "Open Settings"
        ChatErrorPrimaryAction.CHOOSE_PROVIDER -> "Choose provider"
        ChatErrorPrimaryAction.CHOOSE_MODEL -> "Choose model"
        ChatErrorPrimaryAction.EDIT_MESSAGE -> "Edit message"
        ChatErrorPrimaryAction.RETRY -> "Retry"
        ChatErrorPrimaryAction.NONE -> null
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, AccentRed.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = AccentRed)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = error.title(),
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
            if (error.partialMessageId != null) {
                Text(
                    text = "Incomplete response",
                    color = AccentCyan,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Text(text = error.guidance(), color = TextSecondary, fontSize = 13.sp)
            if (phase is ChatErrorUiState.Phase.WaitingUntil && waitSecondsLeft > 0L) {
                Text(
                    text = "Retry available in ${waitSecondsLeft}s",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (actionLabel != null) {
                    Button(
                        onClick = onPrimary,
                        enabled = !(retryHeld && error.primaryAction() == ChatErrorPrimaryAction.RETRY),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TextPrimary,
                            contentColor = DarkBackground
                        ),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(actionLabel)
                    }
                }
                TextButton(onClick = { detailsExpanded = !detailsExpanded }) {
                    Text(if (detailsExpanded) "Hide details" else "Technical details")
                }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
            if (detailsExpanded) {
                val detail = buildString {
                    append(error.category.code)
                    append(" · ")
                    append(error.provider)
                    error.httpStatus?.let { append(" · HTTP "); append(it) }
                    error.model.takeIf { it.isNotBlank() }?.let { append(" · "); append(it) }
                    error.redactedDetail?.toString()?.takeIf { it.isNotBlank() }?.let {
                        append(" · ")
                        append(it)
                    }
                }
                Text(
                    text = detail,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
