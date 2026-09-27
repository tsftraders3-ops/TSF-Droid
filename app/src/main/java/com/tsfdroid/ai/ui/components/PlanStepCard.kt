package com.tsfdroid.ai.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import com.tsfdroid.ai.data.models.PlanStep
import com.tsfdroid.ai.data.models.StepStatus
import com.tsfdroid.ai.ui.theme.*

enum class StepDisplayState {
    PENDING, RUNNING, COMPLETED, FAILED, AUTO_FIXING, REPAIRED, SKIPPED, BLOCKED
}

fun getDisplayState(step: PlanStep): StepDisplayState {
    val errorText = step.error?.lowercase() ?: ""
    val resultText = step.result?.lowercase() ?: ""

    return when {
        step.status == StepStatus.COMPLETED && (resultText.contains("auto-fixed") || resultText.contains("primary failed") || resultText.contains("repaired")) -> StepDisplayState.REPAIRED
        step.status == StepStatus.COMPLETED && resultText.contains("skipped") -> StepDisplayState.SKIPPED
        step.status == StepStatus.FAILED && errorText.contains("is not registered in ActionDispatcher") -> StepDisplayState.AUTO_FIXING
        step.status == StepStatus.FAILED && errorText.contains("blocked") -> StepDisplayState.BLOCKED
        step.status == StepStatus.COMPLETED -> StepDisplayState.COMPLETED
        step.status == StepStatus.RUNNING -> StepDisplayState.RUNNING
        step.status == StepStatus.FAILED -> StepDisplayState.FAILED
        else -> StepDisplayState.PENDING
    }
}

@Composable
fun PlanStepCard(
    step: PlanStep,
    modifier: Modifier = Modifier,
    editable: Boolean = false,
    onSaveEdit: (description: String, params: Map<String, String>) -> Unit = { _, _ -> },
    onDeleteStep: () -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }
    var isEditing by remember(step.stepId) { mutableStateOf(false) }
    var editDescription by remember(step.stepId, isEditing) { mutableStateOf(step.description) }
    var editParams by remember(step.stepId, isEditing) {
        mutableStateOf(step.params.map { (key, value) -> key to value })
    }
    val displayState = getDisplayState(step)
    val c = AppTheme.colors

    // Aurora status taxonomy — one hue, one meaning: running = lime,
    // completed/verified = teal, failed = error, in-between = amber.
    val (chipStyle, isActive) = when (displayState) {
        StepDisplayState.RUNNING -> AuroraChipStyle.Lime to true
        StepDisplayState.COMPLETED -> AuroraChipStyle.Tertiary to false
        StepDisplayState.FAILED -> AuroraChipStyle.Error to false
        StepDisplayState.AUTO_FIXING -> AuroraChipStyle.Amber to false
        StepDisplayState.BLOCKED -> AuroraChipStyle.Await to false
        StepDisplayState.REPAIRED -> AuroraChipStyle.Tonal to false
        StepDisplayState.SKIPPED -> AuroraChipStyle.Secondary to false
        StepDisplayState.PENDING -> AuroraChipStyle.Outline to false
    }

    // The ONE loud container per screen: the running step gets the hero corner
    // (24/24/24/8) on the tonal surface; awaiting/failed tints follow the chips.
    val containerColor = when {
        isActive -> c.surfaceHigh
        else -> Color.Transparent
    }
    val containerShape = if (isActive) {
        RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 8.dp, bottomEnd = 24.dp)
    } else {
        RoundedCornerShape(12.dp)
    }

    Column(
        modifier
            .fillMaxWidth()
            .background(containerColor, containerShape)
            .clickable(enabled = !isEditing) { expanded = !expanded }
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Aurora step rail: number circle + connector
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(34.dp)) {
                if (displayState == StepDisplayState.COMPLETED ||
                    displayState == StepDisplayState.REPAIRED ||
                    displayState == StepDisplayState.SKIPPED
                ) {
                    Box(
                        Modifier
                            .size(34.dp)
                            .background(c.tertiary, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = c.onTertiary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else if (displayState == StepDisplayState.FAILED || displayState == StepDisplayState.BLOCKED) {
                    Box(
                        Modifier
                            .size(34.dp)
                            .background(c.error, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            tint = c.onError,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else {
                    Box(
                        Modifier
                            .size(34.dp)
                            .background(
                                if (isActive) c.lime else c.surfaceHigh,
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${step.order}",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isActive) c.onLime else c.textSecondary
                        )
                    }
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = step.action,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.4.sp,
                        color = if (isActive) c.textSecondary else c.textSecondary,
                        fontFamily = FontFamily.Monospace
                    )
                    AuroraChip(
                        text = displayState.name,
                        style = chipStyle
                    )
                    Spacer(Modifier.weight(1f))
                    if (editable && !isEditing) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit step",
                            tint = c.textSecondary,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { isEditing = true }
                        )
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete step",
                            tint = c.textSecondary,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { onDeleteStep() }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = step.description,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    lineHeight = 19.sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 2
                )
            }
        }

        AnimatedVisibility(visible = isEditing) {
            Column(modifier = Modifier.padding(top = 12.dp)) {
                HorizontalHairline()

                Text("Action Module: ${step.action}", fontSize = 11.sp, color = c.primary, fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = editDescription,
                    onValueChange = { editDescription = it },
                    label = { Text("Step Description", fontSize = 11.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = c.primary,
                        unfocusedBorderColor = c.outlineVariant,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))
                Text("Parameters", fontSize = 11.sp, color = TextSecondary)

                editParams.forEachIndexed { index, (key, value) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = key,
                            onValueChange = { newKey ->
                                editParams = editParams.toMutableList().also { it[index] = newKey to value }
                            },
                            label = { Text("Key", fontSize = 10.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = c.primary,
                                unfocusedBorderColor = c.outlineVariant,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        OutlinedTextField(
                            value = value,
                            onValueChange = { newValue ->
                                editParams = editParams.toMutableList().also { it[index] = key to newValue }
                            },
                            label = { Text("Value", fontSize = 10.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = c.primary,
                                unfocusedBorderColor = c.outlineVariant,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { editParams = editParams.toMutableList().also { it.removeAt(index) } },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Remove", tint = AccentRed, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                TextButton(
                    onClick = { editParams = editParams + ("" to "") },
                    modifier = Modifier.align(Alignment.Start)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add", tint = c.primary, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Parameter", fontSize = 11.sp, color = c.primary)
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = { isEditing = false },
                        border = BorderStroke(1.5.dp, c.outlineVariant),
                        shape = AuroraPillShape,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text("Cancel", fontSize = 12.sp, color = TextSecondary)
                    }
                    Button(
                        onClick = {
                            val cleanedParams = editParams
                                .map { (k, v) -> k.trim() to v }
                                .filter { it.first.isNotEmpty() }
                                .toMap()
                            val cleanedDescription = editDescription.trim().ifEmpty { step.description }
                            onSaveEdit(cleanedDescription, cleanedParams)
                            isEditing = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = c.primary, contentColor = c.onPrimary),
                        shape = AuroraPillShape
                    ) {
                        Text("Save", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    }
                }
            }
        }

        AnimatedVisibility(visible = expanded && !isEditing) {
            Column(modifier = Modifier.padding(top = 12.dp)) {
                HorizontalHairline()

                Text("Action Module: ${step.action}", fontSize = 11.sp, color = c.primary, fontFamily = FontFamily.Monospace)

                if (step.params.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Parameters:", fontSize = 11.sp, color = TextSecondary)
                    step.params.forEach { (key, valStr) ->
                        Text("- $key: $valStr", fontSize = 11.sp, color = TextPrimary, fontFamily = FontFamily.Monospace)
                    }
                }

                if (step.dependsOn.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Depends On Steps: ${step.dependsOn.joinToString()}", fontSize = 11.sp, color = TextSecondary, fontFamily = FontFamily.Monospace)
                }

                if (step.canParallelize) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Parallel execution supported", fontSize = 11.sp, color = c.tertiary)
                }

                if (step.fallback.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Fallback Routine:", fontSize = 11.sp, color = TextSecondary)
                    Text(step.fallback, fontSize = 11.sp, color = TextPrimary, fontFamily = FontFamily.Monospace)
                }

                if (step.result != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(c.surface)
                            .padding(8.dp)
                    ) {
                        Column {
                            Text("Execution Result:", fontSize = 10.sp, color = c.tertiary, fontWeight = FontWeight.Bold)
                            Text(step.result!!, fontSize = 11.sp, color = TextPrimary)
                        }
                    }
                }

                if (step.error != null) {
                    Spacer(modifier = Modifier.height(8.dp))

                    val isHallucinationError = displayState == StepDisplayState.AUTO_FIXING
                    val errorBg = if (isHallucinationError) c.amberContainer else c.errorContainer
                    val errorFg = if (isHallucinationError) c.onAmberContainer else c.onErrorContainer
                    val errorTextDisplay = if (isHallucinationError) "Auto-fixing: The requested system action is currently being recovered and updated by the Repair Engine." else step.error!!

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(errorBg)
                            .padding(10.dp)
                    ) {
                        Column {
                            Text(
                                text = if (isHallucinationError) "Repair Phase Active" else "Execution Error:",
                                fontSize = 10.sp,
                                color = errorFg,
                                fontWeight = FontWeight.Bold
                            )
                            Text(errorTextDisplay, fontSize = 11.sp, color = errorFg)
                        }
                    }
                }
            }
        }
    }
}

/** 1.5dp hairline divider in the Aurora outline-variant tone. */
@Composable
private fun HorizontalHairline() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .height(1.5.dp)
            .background(AppTheme.colors.outlineVariant.copy(alpha = 0.55f))
    )
}
