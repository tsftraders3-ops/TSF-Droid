package com.tsfdroid.ai.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addCircle
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * The TSF Droid agent mark — the exact stroke droid from the Aurora prototype
 * (rounded head, two dot eyes, smile, side + top antennas).
 * Stroke 1.6, round caps/joins, fill none — eyes are filled dots.
 */
fun auroraDroidIcon(): ImageVector = ImageVector.Builder(
    name = "AuroraDroid",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).apply {
    fun strokePath(block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) { block() }
    }
    // head + body outline: M12 3.5c-3.9 0-7 3-7 6.8V17a2.5 2.5 0 0 0 2.5 2.5h9A2.5 2.5 0 0 0 19 17v-6.7c0-3.8-3.1-6.8-7-6.8Z
    strokePath {
        moveTo(12f, 3.5f)
        curveToRelative(-3.9f, 0f, -7f, 3f, -7f, 6.8f)
        verticalLineTo(17f)
        arcToRelative(2.5f, 2.5f, 0f, false, false, 2.5f, 2.5f)
        horizontalLineToRelative(9f)
        arcToRelative(2.5f, 2.5f, 0f, false, false, 2.5f, -2.5f)
        verticalLineToRelative(-6.7f)
        curveToRelative(0f, -3.8f, -3.1f, -6.8f, -7f, -6.8f)
        close()
    }
    // eyes (filled dots)
    addCircle(centerX = 9f, centerY = 11.5f, radius = 1.15f, fill = SolidColor(Color.Black))
    addCircle(centerX = 15f, centerY = 11.5f, radius = 1.15f, fill = SolidColor(Color.Black))
    // smile: M9.5 15.5c.7.7 1.6 1 2.5 1s1.8-.3 2.5-1
    strokePath {
        moveTo(9.5f, 15.5f)
        curveToRelative(0.7f, 0.7f, 1.6f, 1f, 2.5f, 1f)
        reflectiveCurveToRelative(1.8f, -0.3f, 2.5f, -1f)
    }
    // side antennas: M5 9.5 3.2 8M19 9.5 20.8 8
    strokePath {
        moveTo(5f, 9.5f)
        lineTo(3.2f, 8f)
        moveTo(19f, 9.5f)
        lineTo(20.8f, 8f)
    }
    // top antennas: M8.6 3.9 7.7 2.2M15.4 3.9l.9-1.7
    strokePath {
        moveTo(8.6f, 3.9f)
        lineTo(7.7f, 2.2f)
        moveTo(15.4f, 3.9f)
        lineToRelative(0.9f, -1.7f)
    }
}.build()

/**
 * The Aurora blob — the agent's identity. Gradient-filled organic blob
 * (CSS border-radius 58% 42% 55% 45% / 46% 54% 46% 54% in the prototype)
 * that morphs continuously through the prototype's three keyframes.
 * Lives on the onboarding hero, the chat greeting avatar, and mini avatars
 * on titled response cards.
 */
@Composable
fun AuroraBlob(
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    iconSize: Dp = 32.dp,
    morph: Boolean = true
) {
    val colors = com.tsfdroid.ai.ui.theme.AppTheme.colors
    val brush = Brush.linearGradient(
        colors = colors.blobGradient,
        start = Offset(0f, 0f),
        end = Offset(1f, 1f)
    )
    val transition = rememberInfiniteTransition(label = "blobMorph")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "blobPhase"
    )
    // Phase walks K0 → K1 → K2 → K1 → K0 … like the CSS keyframes with `alternate`
    val phase = if (morph) {
        when {
            t < 1f / 3f -> t * 3f                     // K0 → K1
            t < 2f / 3f -> (t - 1f / 3f) * 3f         // K1 → K2
            else -> 1f - (t - 2f / 3f) * 3f           // K2 → K1
        }
    } else 0f

    val shape = BlobShape(if (morph) phase else 0f)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(brush),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = auroraDroidIcon(),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(iconSize)
        )
    }
}

/** Explicit Shape — avoids GenericShape lambda-signature ambiguity across compose versions. */
private class BlobShape(private val phase: Float) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: androidx.compose.ui.unit.Density
    ): Outline {
        val p = Path()
        blobPath(p, size, phase)
        return Outline.Generic(p)
    }
}

private fun lerp(a: Float, b: Float, f: Float): Float = a + (b - a) * f

/**
 * Blob geometry, matching the CSS `border-radius: x1% x2% x3% x4% / y1% y2% y3% y4%`
 * rendering: four elliptical corner arcs, radii expressed as percentages of the size.
 * Morph interpolates the eight percentages between the prototype keyframes:
 *   K0 58/42/55/45 · 46/54/46/54   K1 44/56/40/60 · 56/44/58/42
 *   K2 52/48/60/40 · 42/60/40/60
 */
private fun blobPath(p: Path, s: Size, phase: Float) {
    val x1 = lerp(58f, 44f, phase); val x2 = lerp(42f, 56f, phase)
    val x3 = lerp(55f, 40f, phase); val x4 = lerp(45f, 60f, phase)
    val y1 = lerp(46f, 56f, phase); val y2 = lerp(54f, 44f, phase)
    val y3 = lerp(46f, 58f, phase); val y4 = lerp(54f, 42f, phase)
    val w = s.width; val h = s.height

    // corner radii (percent → px). TL uses (x1,y1) TR (x2,y2) BR (x3,y3) BL (x4,y4)
    val tlX = x1 / 100f * w; val tlY = y1 / 100f * h
    val trX = x2 / 100f * w; val trY = y2 / 100f * h
    val brX = x3 / 100f * w; val brY = y3 / 100f * h
    val blX = x4 / 100f * w; val blY = y4 / 100f * h

    p.moveTo(tlX, 0f)
    // TL arc: top edge point → left edge point (270° → 180°, sweep -90)
    p.arcTo(Rect(0f, 0f, 2f * tlX, 2f * tlY), 270f, -90f, false)
    // BL arc: left edge point → bottom edge point (180° → 90°, sweep -90 going back)
    p.arcTo(Rect(0f, h - 2f * blY, 2f * blX, h), 180f, -90f, false)
    // BR arc: bottom edge point → right edge point (90° → 0°, sweep -90)
    p.arcTo(Rect(w - 2f * brX, h - 2f * brY, w, h), 90f, -90f, false)
    // TR arc: right edge point → top edge point (0° → 270°, sweep -90)
    p.arcTo(Rect(w - 2f * trX, 0f, w, 2f * trY), 0f, -90f, false)
    p.close()
}
