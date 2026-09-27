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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val statusColor = when (plan.status) {
                    PlanStatus.COMPLETED -> c.tertiary
                    PlanStatus.RUNNING -> c.textPrimary
                    PlanStatus.FAILED -> c.error
                    PlanStatus.CANCELLED -> c.onAmberContainer
                    else -> TextSecondary
                }
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(statusColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = plan.status.name,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = statusColor
                )
            }
            if (!isCurrentActive) {
                com.tsfdroid.ai.ui.components.AuroraChip(
                    text = "Viewing Past Run",
                    style = com.tsfdroid.ai.ui.components.AuroraChipStyle.Tonal,
                    modifier = Modifier.clickable { onClearSelection() }
                )
            } else {
                com.tsfdroid.ai.ui.components.AuroraChip(
                    text = "Active run",
                    style = com.tsfdroid.ai.ui.components.AuroraChipStyle.Tertiary
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = plan.goal,
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(12.dp))
        // Aurora stepped-dot track — progress is shape, not prose
        com.tsfdroid.ai.ui.components.PlanDotTrack(
            done = plan.steps.count { it.status == StepStatus.COMPLETED },
            active = plan.steps.indexOfFirst { it.status == StepStatus.RUNNING } + 1,
            steps = plan.steps.size,
            color = c.primary
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("Steps", fontSize = 10.sp, color = TextSecondary)
                Text("${plan.steps.size} scheduled", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Estimated duration", fontSize = 10.sp, color = TextSecondary)
                Text(plan.estimatedDuration, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            }
        }

        if (isCurrentActive && plan.status == PlanStatus.RUNNING) {
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = c.error, contentColor = c.onError),
                shape = com.tsfdroid.ai.ui.components.AuroraPillShape,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Stop task",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
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
