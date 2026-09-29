package com.tsfdroid.ai.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tsfdroid.ai.core.harness.ActivityStep
import com.tsfdroid.ai.data.models.Plan
import com.tsfdroid.ai.data.models.StepStatus
import com.tsfdroid.ai.ui.theme.*

/**
 * v1.2.1: the visible-work surface — Claude / OpenCode-style step rows for
 * everything the agent did to produce a reply (tool calls, output-limit
 * continuations, context compactions, plan/todo steps).
 *
 * Used in two places:
 *  - LIVE under the thinking bubble while the turn runs ([AgentLoop.activitySteps]);
 *  - persisted, inside an agent bubble's collapsible ACTIVITY section
 *    ([com.tsfdroid.ai.data.models.ChatMessage.stepsJson]).
 *
 * Kept deliberately quiet (one line per step, secondary colors, tiny dots) so
 * activity reads as texture, never as hodgepodge.
 */
@Composable
fun AgentActivityList(
    steps: List<ActivityStep>,
    modifier: Modifier = Modifier
) {
    if (steps.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        steps.forEach { step ->
            ActivityRow(step)
        }
    }
}

@Composable
private fun ActivityRow(step: ActivityStep) {
    val running = step.status == ActivityStep.STATUS_RUNNING
    Row(verticalAlignment = Alignment.Top) {
        StepDot(step)
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = step.label,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.Medium,
                color = if (running) AuroraLime else TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (step.detail.isNotBlank()) {
                Text(
                    text = step.detail,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    color = TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = kindLabel(step.kind),
            fontSize = 9.sp,
            color = TextSecondary.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
private fun StepDot(step: ActivityStep) {
    val running = step.status == ActivityStep.STATUS_RUNNING
    val error = step.status == ActivityStep.STATUS_ERROR
    val color = when {
        error -> AccentRed
        running -> AuroraLime
        else -> AccentNeonGreen
    }
    if (running) {
        val transition = rememberInfiniteTransition(label = "step-pulse")
        val alpha by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
            label = "step-alpha"
        )
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(7.dp)
                .alpha(alpha)
                .clip(CircleShape)
                .background(color)
        )
    } else {
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
    }
}

private fun kindLabel(kind: String): String = when (kind) {
    ActivityStep.KIND_TOOL -> "tool"
    ActivityStep.KIND_CONTINUATION -> "auto-continue"
    ActivityStep.KIND_COMPACTION -> "compact"
    ActivityStep.KIND_PLAN_STEP -> "step"
    ActivityStep.KIND_VISION -> "vision"
    ActivityStep.KIND_EXPANSION -> "expand"
    else -> kind
}

/**
 * v1.2.1: the agent-mode TODO LIST, live in chat — the same plan the Plan tab
 * shows, rendered as a compact checklist so the user can watch the agent work
 * through its plan without leaving the conversation. Aurora components only.
 */
@Composable
fun AgentTodoChecklist(
    plan: Plan,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(AuroraSurfaceHigh)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "TODO",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                color = AuroraLime
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${plan.steps.count { it.status == StepStatus.COMPLETED }}/${plan.steps.size}",
                fontSize = 10.sp,
                color = TextSecondary
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = plan.goal,
            fontSize = 13.sp,
            lineHeight = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(10.dp))
        PlanDotTrack(
            done = plan.steps.count { it.status == StepStatus.COMPLETED },
            active = plan.steps.indexOfFirst { it.status == StepStatus.RUNNING }.let { if (it < 0) -1 else it + 1 },
            steps = plan.steps.size,
            color = AuroraPrimary
        )
        Spacer(modifier = Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            plan.steps.sortedBy { it.order }.forEach { step ->
                TodoStepRow(step)
            }
        }
    }
}

@Composable
private fun TodoStepRow(step: com.tsfdroid.ai.data.models.PlanStep) {
    val color = when (step.status) {
        StepStatus.COMPLETED -> AccentNeonGreen
        StepStatus.RUNNING -> AuroraLime
        StepStatus.FAILED -> AccentRed
        StepStatus.PENDING -> TextSecondary.copy(alpha = 0.45f)
    }
    val textAlpha = if (step.status == StepStatus.PENDING) 0.65f else 1f
    Row(verticalAlignment = Alignment.Top) {
        // Checkbox affordance: filled dot = done, line = failed/running,
        // empty = pending (Claude/ChatGPT todo-list grammar).
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(14.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(color.copy(alpha = if (step.status == StepStatus.PENDING) 0f else 0.18f))
                .padding(2.dp)
        ) {
            if (step.status == StepStatus.COMPLETED || step.status == StepStatus.FAILED) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(color)
                        .align(Alignment.Center)
                )
            }
            if (step.status == StepStatus.COMPLETED) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(color)
                        .align(Alignment.Center)
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = step.description,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            color = if (step.status == StepStatus.FAILED) AccentRed else TextPrimary,
            modifier = Modifier
                .weight(1f)
                .alpha(textAlpha),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
