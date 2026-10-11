package com.tsfdroid.ai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tsfdroid.ai.core.canvas.CanvasArtifact
import com.tsfdroid.ai.data.models.PlanStep
import com.tsfdroid.ai.data.models.StepStatus
import com.tsfdroid.ai.ui.theme.AppTheme
import com.tsfdroid.ai.ui.viewmodel.CanvasViewModel

/**
 * God-Tier Phase 2, ticket 5.2 — the Canvas workspace.
 *
 * A split-screen / adaptive workspace: the chat surface on one side, the live
 * [CanvasArtifact] viewer on the other. Layout rules (the behavior the UI
 * tests pin):
 *
 *  - WIDE (>= [EXPANDED_BREAKPOINT_DP]): the two panes share the screen —
 *    chat on the left, the artifact pane always laid out on the right.
 *  - COMPACT: the artifact pane is NOT laid out until the user explicitly
 *    opens it; while open it replaces the chat pane, and closing it returns
 *    to the chat. This is the "hide artifact pane on small screens" bar —
 *    hiding means ABSENT from the tree, not zero-sized.
 *
 * The chat pane is a slot with a production default that embeds the real
 * ChatScreen; instrumentation tests substitute a stand-in because the
 * default pulls a Hilt-scoped ChatViewModel. The layout, tags, and artifact
 * rendering under test are all production code.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CanvasScreen(
    viewModel: CanvasViewModel,
    onBack: () -> Unit,
    chatPane: @Composable (Modifier) -> Unit = { paneModifier ->
        val chatViewModel: com.tsfdroid.ai.ui.viewmodel.ChatViewModel =
            androidx.hilt.navigation.compose.hiltViewModel()
        ChatScreen(viewModel = chatViewModel, modifier = paneModifier)
    }
) {
    val artifact by viewModel.artifact.collectAsState()
    CanvasLayout(
        artifact = artifact,
        onBack = onBack,
        onClearArtifact = viewModel::clearArtifact,
        chatPane = chatPane
    )
}

/** The width at which the canvas splits into two panes; below it, panes swap full-screen. */
private val EXPANDED_BREAKPOINT_DP = 720.dp

@Composable
internal fun CanvasLayout(
    artifact: CanvasArtifact?,
    onBack: () -> Unit,
    onClearArtifact: () -> Unit,
    chatPane: @Composable (Modifier) -> Unit
) {
    // Survives rotation and pane swaps: an explicitly-opened artifact pane on
    // a compact screen must stay open when the artifact updates.
    var artifactPaneOpen by rememberSaveable { mutableStateOf(false) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(AppTheme.colors.background)
            .testTag("canvas_root")
    ) {
        val split = maxWidth >= EXPANDED_BREAKPOINT_DP
        if (split) {
            Row(Modifier.fillMaxSize()) {
                ChatPaneHost(
                    modifier = Modifier
                        .weight(WIDE_CHAT_WEIGHT)
                        .fillMaxHeight(),
                    content = chatPane
                )
                Spacer(Modifier.width(1.dp).fillMaxHeight().background(AppTheme.colors.outlineVariant))
                ArtifactPaneHost(
                    modifier = Modifier
                        .weight(WIDE_ARTIFACT_WEIGHT)
                        .fillMaxHeight(),
                    artifact = artifact,
                    onClose = null
                )
            }
        } else {
            // Compact: the canvas keeps its own slim top bar (title + back to
            // the dashboard) above the single pane, so the user is never
            // stranded without a visible exit — and the surface is
            // identifiable as the Canvas, not an anonymous chat clone.
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = {
                        Text(
                            text = "Canvas",
                            style = MaterialTheme.typography.titleMedium,
                            color = AppTheme.colors.textPrimary
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to dashboard",
                                tint = AppTheme.colors.textPrimary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = AppTheme.colors.background
                    )
                )
                Box(Modifier.weight(1f)) {
                    // The artifact pane only exists while explicitly opened;
                    // the chat pane hosts an affordance to open it whenever an
                    // artifact is available.
                    if (artifactPaneOpen && artifact != null) {
                        ArtifactPaneHost(
                            modifier = Modifier.fillMaxSize(),
                            artifact = artifact,
                            onClose = {
                                artifactPaneOpen = false
                                onClearArtifact()
                            }
                        )
                    } else {
                        ChatPaneHost(
                            modifier = Modifier.fillMaxSize(),
                            content = { paneModifier ->
                                Box(paneModifier) {
                                    chatPane(Modifier.fillMaxSize())
                                    if (artifact != null) {
                                        OpenArtifactButton(
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(16.dp),
                                            onClick = { artifactPaneOpen = true }
                                        )
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

/** The chat/status side of the canvas. Pane identity is production-owned (tag lives here). */
@Composable
private fun ChatPaneHost(
    modifier: Modifier,
    content: @Composable (Modifier) -> Unit
) {
    Box(modifier.testTag("canvas_chat_pane")) {
        content(Modifier.fillMaxSize())
    }
}

/** The artifact side of the canvas. Pane identity is production-owned (tag lives here). */
@Composable
private fun ArtifactPaneHost(
    modifier: Modifier,
    artifact: CanvasArtifact?,
    onClose: (() -> Unit)?
) {
    Box(modifier.testTag("canvas_artifact_pane")) {
        Scaffold(
            containerColor = AppTheme.colors.surfaceLow,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "Canvas",
                            style = MaterialTheme.typography.headlineSmall,
                            color = AppTheme.colors.textPrimary
                        )
                    },
                    navigationIcon = {
                        if (onClose != null) {
                            IconButton(
                                onClick = onClose,
                                modifier = Modifier.testTag("canvas_close_artifact")
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Close artifact",
                                    tint = AppTheme.colors.textPrimary
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = AppTheme.colors.surfaceLow
                    )
                )
            }
        ) { padding ->
            when (val current = artifact) {
                null -> EmptyArtifactState(Modifier.padding(padding))
                else -> ArtifactViewer(
                    artifact = current,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                )
            }
        }
    }
}

@Composable
private fun EmptyArtifactState(modifier: Modifier) {
    Box(
        modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Description,
                contentDescription = null,
                tint = AppTheme.colors.textSecondary,
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "No artifact yet",
                style = MaterialTheme.typography.titleMedium,
                color = AppTheme.colors.textSecondary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Plans and files the agent produces appear here",
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.textSecondary
            )
        }
    }
}

@Composable
private fun OpenArtifactButton(modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier
            .testTag("canvas_open_artifact")
            .clip(RoundedCornerShape(999.dp)),
        color = AppTheme.colors.primaryContainer,
        shape = RoundedCornerShape(999.dp),
        onClick = onClick
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.ListAlt,
                contentDescription = null,
                tint = AppTheme.colors.onPrimaryContainer,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "View artifact",
                color = AppTheme.colors.onPrimaryContainer,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * Switches rendering on the artifact type: plans render as live step lists,
 * text as titled prose, files as a file header plus content preview.
 */
@Composable
fun ArtifactViewer(
    artifact: CanvasArtifact,
    modifier: Modifier = Modifier
) {
    when (artifact) {
        is CanvasArtifact.PlanArtifact -> PlanArtifactViewer(artifact, modifier)
        is CanvasArtifact.TextArtifact -> TextArtifactViewer(artifact, modifier)
        is CanvasArtifact.FileArtifact -> FileArtifactViewer(artifact, modifier)
    }
}

@Composable
private fun PlanArtifactViewer(
    artifact: CanvasArtifact.PlanArtifact,
    modifier: Modifier
) {
    val plan = artifact.plan
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = plan.goal,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = AppTheme.colors.textPrimary
        )
        Text(
            text = "${plan.steps.size} steps · ${plan.estimatedDuration}",
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.colors.textSecondary
        )
        Spacer(Modifier.height(4.dp))
        plan.steps.sortedBy { it.order }.forEach { step ->
            PlanStepRow(step)
        }
    }
}

@Composable
private fun PlanStepRow(step: PlanStep) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("canvas_plan_step"),
        color = AppTheme.colors.background,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StepStatusIndicator(step.status)
            Column(Modifier.weight(1f)) {
                Text(
                    text = step.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTheme.colors.textPrimary
                )
                if (step.error.isNullOrBlank().not()) {
                    Text(
                        text = step.error!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.error
                    )
                }
            }
        }
    }
}

@Composable
private fun StepStatusIndicator(status: StepStatus) {
    when (status) {
        StepStatus.COMPLETED -> Text(
            "✓",
            color = AppTheme.colors.primaryContainer,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
        StepStatus.RUNNING -> CircularProgressIndicator(
            strokeWidth = 2.dp,
            modifier = Modifier.size(14.dp)
        )
        StepStatus.FAILED -> Text(
            "✕",
            color = AppTheme.colors.error,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
        StepStatus.PENDING -> Text(
            "•",
            color = AppTheme.colors.textSecondary,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
    }
}

@Composable
private fun TextArtifactViewer(
    artifact: CanvasArtifact.TextArtifact,
    modifier: Modifier
) {
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = artifact.title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = AppTheme.colors.textPrimary
        )
        Text(
            text = artifact.body,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.SansSerif,
            lineHeight = 21.sp,
            color = AppTheme.colors.textPrimary
        )
    }
}

@Composable
private fun FileArtifactViewer(
    artifact: CanvasArtifact.FileArtifact,
    modifier: Modifier
) {
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.InsertDriveFile,
                contentDescription = null,
                tint = AppTheme.colors.textSecondary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = artifact.fileName,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = AppTheme.colors.textPrimary
            )
        }
        Text(
            text = formatSize(artifact.sizeBytes),
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.colors.textSecondary
        )
        Surface(
            color = AppTheme.colors.background,
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = artifact.content,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                lineHeight = 18.sp,
                color = AppTheme.colors.textPrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            )
        }
    }
}

private fun formatSize(sizeBytes: Long): String = when {
    sizeBytes >= 1_048_576 -> "%.1f MB".format(sizeBytes / 1_048_576.0)
    sizeBytes >= 1_024 -> "%.1f KB".format(sizeBytes / 1_024.0)
    else -> "$sizeBytes B"
}

/** Wide layout: 45% chat / 55% artifact. */
private val WIDE_CHAT_WEIGHT = 0.45f
private val WIDE_ARTIFACT_WEIGHT = 0.55f
