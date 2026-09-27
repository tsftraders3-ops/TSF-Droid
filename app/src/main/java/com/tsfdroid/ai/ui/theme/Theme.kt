package com.tsfdroid.ai.ui.theme

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * Applies edge-to-edge using the app's selected theme rather than the device's system theme.
 *
 * AndroidX uses the system UI mode to select the API 26-28 navigation-bar scrim by default.
 * The app supports an in-app theme toggle, so use the selected palette instead. On API 29+
 * [SystemBarStyle.auto] keeps both bars transparent, preserving edge-to-edge behavior.
 */
internal fun ComponentActivity.enableOpenDroidEdgeToEdge(isDarkTheme: Boolean) {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.auto(
            lightScrim = Color.TRANSPARENT,
            darkScrim = Color.TRANSPARENT,
            detectDarkMode = { isDarkTheme }
        ),
        navigationBarStyle = SystemBarStyle.auto(
            lightScrim = LightPalette.background.toArgb(),
            darkScrim = DarkPalette.background.toArgb(),
            detectDarkMode = { isDarkTheme }
        )
    )
}

/**
 * Aurora shape hierarchy — radius is a HIERARCHY that encodes interactivity scale:
 *   30px = hero interactive moment (one per screen: approve, send, gate)
 *   20px = content containers   · 16px = tiles   · 12px = dense rows
 *   999px = selection surfaces (chips, tabs, toggles, nav indicator)
 *   Asymmetric radii encode meaning (chat bubbles, hero corners).
 */
object AuroraShapes {
    val hero = RoundedCornerShape(30.dp)
    val card = RoundedCornerShape(20.dp)
    val tile = RoundedCornerShape(16.dp)
    val row = RoundedCornerShape(12.dp)
    val pill = RoundedCornerShape(999.dp)
    /** The ONE active container per screen: running plan step, open tier, running macro. */
    val heroCorner = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 24.dp, bottomEnd = 8.dp)
    /** Chat bubbles — the flat corner points at the speaker (agent left, user right). */
    val bubbleAgent = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp, bottomStart = 6.dp, bottomEnd = 30.dp)
    val bubbleUser = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp, bottomStart = 30.dp, bottomEnd = 6.dp)
    /** Bottom sheet crown. */
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
}

/**
 * Aurora motion tokens:
 *   · emph   cubic-bezier(.2,0,0,1)       M3 emphasized — screen entrances (240ms)
 *   · spring cubic-bezier(.34,1.56,.64,1) overshoot — button/check/toggle answers
 */
object AuroraMotion {
    val EasingEmphasized = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)
    const val DurationScreenEnter = 240
    const val DurationFast = 160
    const val DurationSlow = 300
}

val LocalAuroraShapes = staticCompositionLocalOf { AuroraShapes }

/** Access the Aurora shape tokens from any @Composable: `AuroraTheme.shapes.card`. */
object AuroraTheme {
    val shapes: AuroraShapeSet
        @Composable
        @ReadOnlyComposable
        get() = LocalAuroraShapeSet.current
}

/** Composable-facing shape token bundle. */
data class AuroraShapeSet(
    val hero: RoundedCornerShape,
    val card: RoundedCornerShape,
    val tile: RoundedCornerShape,
    val row: RoundedCornerShape,
    val pill: RoundedCornerShape,
    val heroCorner: RoundedCornerShape,
    val bubbleAgent: RoundedCornerShape,
    val bubbleUser: RoundedCornerShape,
    val sheet: RoundedCornerShape
)

val LocalAuroraShapeSet = staticCompositionLocalOf {
    AuroraShapeSet(
        hero = AuroraShapes.hero,
        card = AuroraShapes.card,
        tile = AuroraShapes.tile,
        row = AuroraShapes.row,
        pill = AuroraShapes.pill,
        heroCorner = AuroraShapes.heroCorner,
        bubbleAgent = AuroraShapes.bubbleAgent,
        bubbleUser = AuroraShapes.bubbleUser,
        sheet = AuroraShapes.sheet
    )
}

private val M3Shapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPalette.primary,
    onPrimary = DarkPalette.onPrimary,
    primaryContainer = DarkPalette.primaryContainer,
    onPrimaryContainer = DarkPalette.onPrimaryContainer,
    secondary = DarkPalette.secondary,
    onSecondary = DarkPalette.textPrimary,
    secondaryContainer = DarkPalette.secondaryContainer,
    onSecondaryContainer = DarkPalette.onSecondaryContainer,
    tertiary = DarkPalette.tertiary,
    onTertiary = DarkPalette.onTertiary,
    tertiaryContainer = DarkPalette.tertiaryContainer,
    onTertiaryContainer = DarkPalette.onTertiaryContainer,
    background = DarkPalette.background,
    surface = DarkPalette.surface,
    surfaceVariant = DarkPalette.surfaceHigh,
    onBackground = DarkPalette.textPrimary,
    onSurface = DarkPalette.textPrimary,
    onSurfaceVariant = DarkPalette.textSecondary,
    outline = DarkPalette.outline,
    outlineVariant = DarkPalette.outlineVariant,
    error = DarkPalette.error,
    onError = DarkPalette.onError,
    errorContainer = DarkPalette.errorContainer,
    onErrorContainer = DarkPalette.onErrorContainer
)

private val LightColorScheme = lightColorScheme(
    primary = LightPalette.primary,
    onPrimary = LightPalette.onPrimary,
    primaryContainer = LightPalette.primaryContainer,
    onPrimaryContainer = LightPalette.onPrimaryContainer,
    secondary = LightPalette.secondary,
    onSecondary = LightPalette.textPrimary,
    secondaryContainer = LightPalette.secondaryContainer,
    onSecondaryContainer = LightPalette.onSecondaryContainer,
    tertiary = LightPalette.tertiary,
    onTertiary = LightPalette.onTertiary,
    tertiaryContainer = LightPalette.tertiaryContainer,
    onTertiaryContainer = LightPalette.onTertiaryContainer,
    background = LightPalette.background,
    surface = LightPalette.surface,
    surfaceVariant = LightPalette.surfaceHigh,
    onBackground = LightPalette.textPrimary,
    onSurface = LightPalette.textPrimary,
    onSurfaceVariant = LightPalette.textSecondary,
    outline = LightPalette.outline,
    outlineVariant = LightPalette.outlineVariant,
    error = LightPalette.error,
    onError = LightPalette.onError,
    errorContainer = LightPalette.errorContainer,
    onErrorContainer = LightPalette.onErrorContainer
)

@Composable
fun OpenDroidTheme(
    isDarkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val palette = if (isDarkTheme) DarkPalette else LightPalette
    val colorScheme = if (isDarkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    val activity = view.context as? ComponentActivity
    if (!view.isInEditMode && activity != null) {
        SideEffect {
            activity.enableOpenDroidEdgeToEdge(isDarkTheme)
            val window = activity.window
            val insetsController = WindowCompat.getInsetsController(window, view)
            // Light status bar icons for dark theme, dark icons for light theme
            insetsController.isAppearanceLightStatusBars = !isDarkTheme
            insetsController.isAppearanceLightNavigationBars = !isDarkTheme
        }
    }

    CompositionLocalProvider(LocalOpenDroidColors provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = M3Shapes,
            content = content
        )
    }
}
