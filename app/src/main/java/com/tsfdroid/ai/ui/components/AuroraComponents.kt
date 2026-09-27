package com.tsfdroid.ai.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tsfdroid.ai.ui.theme.AppTheme
import com.tsfdroid.ai.ui.theme.AuroraType
import com.tsfdroid.ai.ui.theme.BricolageGrotesque

/**
 * Aurora chip taxonomy (prototype): chips/tints are a STATE TAXONOMY —
 * PROPOSED / RUNNING / AWAITING / COMPLETED / FAILED, granted/partial/missing —
 * they encode information, never decorate.
 */
enum class AuroraChipStyle { Tonal, Tertiary, Lime, Error, Amber, Secondary, Await, Outline }

@Composable
fun AuroraChip(
    text: String,
    modifier: Modifier = Modifier,
    style: AuroraChipStyle = AuroraChipStyle.Tonal,
    leading: ImageVector? = null
) {
    val c = AppTheme.colors
    val (bg, fg) = when (style) {
        AuroraChipStyle.Tonal -> c.primaryContainer to c.onPrimaryContainer
        AuroraChipStyle.Tertiary -> c.tertiaryContainer to c.onTertiaryContainer
        AuroraChipStyle.Lime -> c.lime to c.onLime
        AuroraChipStyle.Error -> c.errorContainer to c.onErrorContainer
        AuroraChipStyle.Amber -> c.amberContainer to c.onAmberContainer
        AuroraChipStyle.Secondary -> c.secondaryContainer to c.onSecondaryContainer
        AuroraChipStyle.Await -> c.error to c.onError
        AuroraChipStyle.Outline -> Color.Transparent to c.textSecondary
    }
    val base: Modifier = if (style == AuroraChipStyle.Outline) {
        modifier.border(1.5.dp, c.outlineVariant, AuroraPillShape)
    } else {
        modifier.background(bg, AuroraPillShape)
    }
    Row(
        base
            .padding(horizontal = 11.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (leading != null) {
            Icon(leading, contentDescription = null, tint = fg, modifier = Modifier.size(12.dp))
        }
        Text(text, color = fg, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 14.sp)
    }
}

/** 999px selection-surface shape shared by chips, tabs, toggles and the nav indicator. */
val AuroraPillShape: RoundedCornerShape = RoundedCornerShape(999.dp)

/**
 * Aurora buttons. `hero` is the ONE hero interactive moment per screen —
 * 30px radius, 54px tall, primary fill (prototype `.btn.hero`).
 */
@Composable
fun AuroraHeroButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    container: Color? = null,
    contentColor: Color? = null
) {
    val c = AppTheme.colors
    Button(
        onClick = onClick,
        modifier = modifier.height(54.dp),
        enabled = enabled,
        shape = RoundedCornerShape(30.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container ?: c.primary,
            contentColor = contentColor ?: c.onPrimary,
            disabledContainerColor = c.outlineVariant,
            disabledContentColor = c.surfaceHighest
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 26.dp)
    ) {
        Text(text, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun AuroraGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = AppTheme.colors.textPrimary,
    height: Int = 46
) {
    val c = AppTheme.colors
    Button(
        onClick = onClick,
        modifier = modifier.height(height.dp),
        enabled = enabled,
        shape = AuroraPillShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = contentColor,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = c.outline
        ),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, c.outlineVariant),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 22.dp)
    ) {
        Text(text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun AuroraSmallButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: AuroraChipStyle = AuroraChipStyle.Tertiary,
    borderOnly: Boolean = false
) {
    val c = AppTheme.colors
    val (bg, fg) = when (style) {
        AuroraChipStyle.Tertiary -> c.tertiary to c.onTertiary
        AuroraChipStyle.Tonal -> c.primary to c.onPrimary
        AuroraChipStyle.Lime -> c.lime to c.onLime
        AuroraChipStyle.Error, AuroraChipStyle.Await -> c.error to c.onError
        AuroraChipStyle.Amber -> c.amberContainer to c.onAmberContainer
        AuroraChipStyle.Secondary -> c.secondaryContainer to c.onSecondaryContainer
        AuroraChipStyle.Outline -> Color.Transparent to c.textPrimary
    }
    val shape = AuroraPillShape
    val base = if (borderOnly || style == AuroraChipStyle.Outline) {
        modifier.border(1.5.dp, if (borderOnly) c.outlineVariant else c.outlineVariant, shape)
    } else modifier
    Box(
        base
            .background(bg, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun AuroraQuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = AppTheme.colors.textPrimary) {
    val c = AppTheme.colors
    Box(
        modifier
            .border(1.5.dp, c.outlineVariant, AuroraPillShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Sentence-case section label (12.5px, ink-2, bold) — never all-caps. */
@Composable
fun AuroraSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(top = 18.dp, bottom = 8.dp),
        color = AppTheme.colors.textSecondary,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.Bold
    )
}

/** Display big title (Bricolage 34px) + big subtitle used at the top of screens. */
@Composable
fun AuroraBigTitle(text: String, modifier: Modifier = Modifier, style: TextStyle = AuroraType.bigTitle) {
    Text(text, modifier = modifier, style = style, color = AppTheme.colors.textPrimary)
}

@Composable
fun AuroraBigSub(text: String, modifier: Modifier = Modifier, marginBottom: Int = 14) {
    Text(
        text,
        modifier = modifier.padding(bottom = marginBottom.dp),
        color = AppTheme.colors.textSecondary,
        fontSize = 14.sp,
        lineHeight = 21.sp
    )
}

/**
 * Plan banner — state-colored hero container (proposed = primary-container,
 * running = LIME, done = teal container, rejected = surface-highest,
 * awaiting = error container). Stepped-dot progress track rides inside:
 * progress is shape, not prose.
 */
enum class PlanBannerState { Proposed, Running, Done, Rejected, Awaiting }

@Composable
fun AuroraPlanBanner(
    title: String,
    subtitle: String,
    state: PlanBannerState,
    modifier: Modifier = Modifier,
    trackDone: Int = 0,
    trackActive: Int = 0,
    trackSteps: Int = 3,
    actions: (@Composable RowScopeAlias.() -> Unit)? = null
) {
    val c = AppTheme.colors
    val (bg, fg) = when (state) {
        PlanBannerState.Proposed -> c.primaryContainer to c.onPrimaryContainer
        PlanBannerState.Running -> c.lime to c.onLime
        PlanBannerState.Done -> c.tertiaryContainer to c.onTertiaryContainer
        PlanBannerState.Rejected -> c.surfaceHighest to c.textPrimary
        PlanBannerState.Awaiting -> c.errorContainer to c.onErrorContainer
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = fg)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = fg.copy(alpha = 0.85f), fontSize = 13.sp, lineHeight = 19.sp)
        if (trackSteps > 0) {
            Spacer(Modifier.height(12.dp))
            PlanDotTrack(done = trackDone, active = trackActive, steps = trackSteps, color = fg)
        }
        if (actions != null) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { actions() }
        }
    }
}

/** Compose has no RowScope alias type; a placeholder SAM so the banner's actions slot reads cleanly. */
typealias RowScopeAlias = androidx.compose.foundation.layout.RowScope

/**
 * Stepped-dot progress track — 15px rounded-square dots (6px radius) linked by
 * hairline segments; the active dot fills + pulses, done dots become circles.
 */
@Composable
fun PlanDotTrack(done: Int, active: Int, steps: Int, color: Color, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        repeat(steps) { i ->
            val step = i + 1
            val isDone = step <= done
            val isActive = step == active
            val dotShape = if (isDone) CircleShape else RoundedCornerShape(6.dp)
            Box(
                Modifier
                    .size(15.dp)
                    .background(if (isDone || isActive) color else color.copy(alpha = 0.25f), dotShape)
                    .border(2.5.dp, color.copy(alpha = if (isDone || isActive) 0f else 0.45f), dotShape)
            )
            if (step < steps) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(2.5.dp)
                        .background(color.copy(alpha = if (step < done) 0.85f else 0.2f), RoundedCornerShape(2.dp))
                )
            }
        }
    }
}

/**
 * Hero-corner container — 24/24/24/8 marks the ONE active container per screen
 * (running/awaiting plan step, open memory tier, running macro).
 */
fun Modifier.auroraHeroCorner(bg: Color): Modifier =
    this.background(bg, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 24.dp, bottomEnd = 8.dp))

/**
 * Critical gate card — the loudest error moment: 8px leading accent bar,
 * error-tinted heading, filled-error confirm button, quiet cancel.
 * (prototype `.gate-critical` — SEND_SMS / PAY_UPI / MAKE_CALL confirmations)
 */
@Composable
fun AuroraGateCritical(
    heading: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    cancelLabel: String? = "Cancel",
    onCancel: (() -> Unit)? = null
) {
    val c = AppTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.background)
            .height(intrinsicSize = androidx.compose.foundation.layout.IntrinsicSize.Min)
    ) {
        Box(
            Modifier
                .width(8.dp)
                .fillMaxHeight()
                .background(c.error)
        )
        Column(Modifier.padding(start = 19.dp, top = 14.dp, end = 16.dp, bottom = 14.dp)) {
                Text(heading, color = c.error, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(body, color = c.textSecondary, fontSize = 13.sp, lineHeight = 19.sp)
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .background(c.error, AuroraPillShape)
                        .clickable(onClick = onConfirm),
                    contentAlignment = Alignment.Center
                ) {
                    Text(confirmLabel, color = c.onError, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                if (cancelLabel != null && onCancel != null) {
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text(cancelLabel, color = c.textSecondary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/**
 * Inline gate card (chat / macro variant) — asymmetric radius 16/28/16/28 = armed.
 */
@Composable
fun AuroraGateInline(
    heading: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    cancelLabel: String? = "Cancel",
    onCancel: (() -> Unit)? = null,
    receipt: Boolean = false
) {
    val c = AppTheme.colors
    val bg = if (receipt) c.tertiaryContainer else c.errorContainer
    val fg = if (receipt) c.onTertiaryContainer else c.onErrorContainer
    val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 28.dp, bottomStart = 16.dp, bottomEnd = 28.dp)
    Column(
        modifier
            .fillMaxWidth()
            .background(bg, shape)
            .padding(13.dp)
    ) {
        Text(heading, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(body, color = fg, fontSize = 12.5.sp, lineHeight = 18.sp)
        if (!receipt) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .background(c.error, AuroraPillShape)
                        .clickable(onClick = onConfirm)
                        .padding(horizontal = 15.dp, vertical = 8.dp)
                ) { Text(confirmLabel, color = c.onError, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                if (cancelLabel != null && onCancel != null) {
                    Box(
                        Modifier
                            .border(1.5.dp, fg.copy(alpha = 0.5f), AuroraPillShape)
                            .clickable(onClick = onCancel)
                            .padding(horizontal = 15.dp, vertical = 8.dp)
                    ) { Text(cancelLabel, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

/**
 * Segmented control (prototype `.seg`) — r-hero container, checked segment
 * fills primary (or lime with `limeOn`) and gains a soft shadow.
 */
@Composable
fun AuroraSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    limeOn: Boolean = false
) {
    val c = AppTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .background(c.surfaceHighest, RoundedCornerShape(30.dp))
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEachIndexed { i, opt ->
            val selected = i == selectedIndex
            val bg = when {
                selected && limeOn -> c.lime
                selected -> c.primary
                else -> Color.Transparent
            }
            val fg = when {
                selected && limeOn -> c.onLime
                selected -> c.onPrimary
                else -> c.textSecondary
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(46.dp)
                    .background(bg, RoundedCornerShape(24.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onSelect(i) },
                contentAlignment = Alignment.Center
            ) {
                Text(opt, color = fg, fontSize = 13.5.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold)
            }
        }
    }
}

/**
 * Aurora switch — 52×32 pill; checked = primary track, 24px on-knob.
 */
@Composable
fun AuroraSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    val track by animateColorAsState(if (checked) c.primary else c.surfaceHighest, tween(160), label = "swTrack")
    val knob by animateColorAsState(if (checked) c.onPrimary else c.outline, tween(160), label = "swKnob")
    val knobAlign = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    Box(
        modifier
            .size(width = 52.dp, height = 32.dp)
            .border(2.dp, if (checked) c.primary else c.outline, AuroraPillShape)
            .background(track, AuroraPillShape)
            .clickable { onCheckedChange(!checked) },
        contentAlignment = knobAlign
    ) {
        Box(
            Modifier
                .padding(horizontal = 3.dp)
                .size(24.dp)
                .background(knob, CircleShape)
        )
    }
}

/** Confidence bar — 7px rounded track, teal fill. */
@Composable
fun AuroraConfBar(fraction: Float, modifier: Modifier = Modifier, fill: Color = AppTheme.colors.tertiary) {
    Box(
        modifier
            .fillMaxWidth()
            .height(7.dp)
            .background(AppTheme.colors.surfaceHighest, AuroraPillShape)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(7.dp)
                .background(fill, AuroraPillShape)
        )
    }
}

/** Typing indicator — three bouncing dots (prototype `.typing`). */
@Composable
fun AuroraTypingDots(modifier: Modifier = Modifier, color: Color = AppTheme.colors.textSecondary) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "typing")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { i ->
            val y by transition.animateFloat(
                initialValue = 0f,
                targetValue = -3f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    animation = tween(600, delayMillis = i * 150, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                ),
                label = "dot$i"
            )
            Box(Modifier.padding(top = 2.dp).graphicsLayerY(y).size(6.dp).background(color, CircleShape))
        }
    }
}

private fun Modifier.graphicsLayerY(y: Float): Modifier =
    this.then(androidx.compose.ui.draw.graphicsLayer { translationY = y * 3f })
