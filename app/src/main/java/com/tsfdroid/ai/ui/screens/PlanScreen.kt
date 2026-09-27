package com.tsfdroid.ai.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tsfdroid.ai.data.models.Plan
import com.tsfdroid.ai.data.models.PlanStatus
import com.tsfdroid.ai.data.models.PlanStep
import com.tsfdroid.ai.data.models.StepStatus
import com.tsfdroid.ai.ui.theme.*
import com.tsfdroid.ai.ui.components.PlanStepCard
import com.tsfdroid.ai.ui.viewmodel.PlanViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(
    viewModel: PlanViewModel,
    modifier: Modifier = Modifier
) {
    val currentPlan by viewModel.currentPlan.collectAsState()
    val planHistory by viewModel.planHistory.collectAsState()
    
    var selectedPlanId by remember { mutableStateOf<String?>(null) }
    val displayPlan = if (selectedPlanId != null) {
        planHistory.find { it.planId == selectedPlanId } ?: currentPlan
    } else {
        currentPlan
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Plan",
                        style = MaterialTheme.typography.headlineSmall,
                        color = TextPrimary
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        },
        containerColor = DarkBackground,
        modifier = modifier
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // Main Section: Current Active Plan
            if (displayPlan != null) {
                val isCurrentActivePlan = displayPlan!!.planId == currentPlan?.planId

                item {
                    PlanHeaderCard(
                        plan = displayPlan!!,
                        isCurrentActive = isCurrentActivePlan,
                        onClearSelection = { selectedPlanId = null },
                        onStop = { viewModel.stopTask() }
                    )
                }

                item {
                    Text(
                        text = "Plan sequence",
                        style = com.tsfdroid.ai.ui.theme.AuroraType.bigTitle31,
                        color = TextPrimary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                }

                items(displayPlan!!.steps, key = { it.stepId }) { step ->
                    val isStepEditable = isCurrentActivePlan &&
                        displayPlan!!.status == PlanStatus.RUNNING &&
                        step.status == StepStatus.PENDING
                    PlanStepCard(
                        step = step,
                        editable = isStepEditable,
                        onSaveEdit = { description, params ->
                            viewModel.editStep(step.stepId, description, params)
                        },
                        onDeleteStep = { viewModel.deleteStep(step.stepId) }
                    )
                }
            } else {
                item {
                    EmptyPlanPlaceholder()
                }
            }

            // History Section: Past Autonomous Runs
            if (planHistory.isNotEmpty()) {
                item {
                    Text(
                        text = "Execution history",
                        style = com.tsfdroid.ai.ui.theme.AuroraType.bigTitle31,
                        color = TextPrimary,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                    )
                }

                items(planHistory) { pastPlan ->
                    val isSelected = selectedPlanId == pastPlan.planId || (selectedPlanId == null && pastPlan.planId == currentPlan?.planId)
                    PastPlanRow(
                        plan = pastPlan,
                        isSelected = isSelected,
                        onSelect = { selectedPlanId = pastPlan.planId },
                        onDelete = { viewModel.deletePlan(pastPlan.planId) }
                    )
                }
            }
        }
    }
}

@Composable
fun PlanHeaderCard(
    plan: Plan,
    isCurrentActive: Boolean,
    onClearSelection: () -> Unit,
    onStop: () -> Unit
) {
    val c = AppTheme.colors

    // Aurora plan banner — the five prototype states:
    // proposed = primary-container, running = LIME, done = teal container,
    // rejected/cancelled = surface-highest, failed = error container.
    val bannerState = when (plan.status) {
        PlanStatus.PROPOSED -> com.tsfdroid.ai.ui.components.PlanBannerState.Proposed
        PlanStatus.RUNNING -> com.tsfdroid.ai.ui.components.PlanBannerState.Running
        PlanStatus.COMPLETED -> com.tsfdroid.ai.ui.components.PlanBannerState.Done
        PlanStatus.CANCELLED, PlanStatus.FAILED -> com.tsfdroid.ai.ui.components.PlanBannerState.Rejected
        else -> com.tsfdroid.ai.ui.components.PlanBannerState.Proposed
    }
    val subtitle = if (isCurrentActive) {
        when (plan.status) {
            PlanStatus.PROPOSED -> "Plan proposed — approve it to run the sequence below."
            PlanStatus.RUNNING -> "Running — each step verifies before the next one moves."
            PlanStatus.COMPLETED -> "Plan completed — every step verified."
            PlanStatus.CANCELLED -> "Stopped — nothing ran after the stop."
            PlanStatus.FAILED -> "Failed — the replan ladder engaged; check the steps below."
            else -> plan.status.name
        }
    } else {
        "Past run — ${plan.steps.size} steps"
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        com.tsfdroid.ai.ui.components.AuroraPlanBanner(
            title = plan.goal,
            subtitle = subtitle,
            state = bannerState,
            trackDone = plan.steps.count { it.status == StepStatus.COMPLETED },
            trackActive = plan.steps.indexOfFirst { it.status == StepStatus.RUNNING } + 1,
            trackSteps = plan.steps.size
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${plan.steps.size} steps — est. ${plan.estimatedDuration}",
                fontSize = 12.sp,
                color = TextSecondary
            )
            if (!isCurrentActive) {
                com.tsfdroid.ai.ui.components.AuroraChip(
                    text = "Viewing Past Run",
                    style = com.tsfdroid.ai.ui.components.AuroraChipStyle.Tonal,
                    modifier = Modifier.clickable { onClearSelection() }
                )
            } else if (plan.status == PlanStatus.RUNNING) {
                com.tsfdroid.ai.ui.components.AuroraChip(
                    text = "Stop task",
                    style = com.tsfdroid.ai.ui.components.AuroraChipStyle.Await,
                    modifier = Modifier.clickable { onStop() }
                )
            }
        }
    }
}

@Composable
fun EmptyPlanPlaceholder() {
    val c = AppTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(20.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.ListAlt,
            contentDescription = "No plan",
            tint = TextSecondary,
            modifier = Modifier.size(36.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "No active plans running",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Plans formulated by the autonomous system will display here in real-time.",
            fontSize = 12.5.sp,
            color = TextSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
fun PastPlanRow(
    plan: Plan,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val c = AppTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) c.surface else Color.Transparent)
            .clickable { onSelect() }
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = plan.goal,
                fontSize = 13.sp,
                color = TextPrimary,
                maxLines = 1,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = dateFormat.format(Date(plan.createdAt)),
                    fontSize = 10.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "${plan.steps.size} steps",
                    fontSize = 10.sp,
                    color = AccentCyan,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = when (plan.status) {
                    PlanStatus.COMPLETED -> Icons.Default.Check
                    PlanStatus.FAILED -> Icons.Default.Close
                    PlanStatus.CANCELLED -> Icons.Default.Cancel
                    else -> Icons.Default.Info
                },
                contentDescription = plan.status.name,
                tint = when (plan.status) {
                    PlanStatus.COMPLETED -> c.tertiary
                    PlanStatus.FAILED -> c.error
                    PlanStatus.CANCELLED -> c.onAmberContainer
                    else -> TextSecondary
                },
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete Plan",
                    tint = TextSecondary.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
