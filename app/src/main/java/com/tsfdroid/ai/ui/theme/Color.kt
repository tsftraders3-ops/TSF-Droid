package com.tsfdroid.ai.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * Aurora design tokens — the exact palette of prototype-a-aurora.html
 * ("TSF Droid — PROTOTYPE A · AURORA", Google-native Material 3 Expressive).
 *
 * COLOR — dynamic-color tonal scheme, all hand-tuned around ONE vivid hue family
 *   plus a surprising tertiary. No gradients-as-design, no cream+terracotta.
 *   · Primary  #3F3FD1  vivid blue-violet (7.4:1 on white → AA at any size)
 *     container #E4E1FF / on-container #0D0D63
 *   · Tertiary #00695B  teal (supporting states) · container #9CF0DC
 *   · Lime     #C9F16F  the "surprise" accent — fill-only, always with dark
 *     ink #253200 on top (≈10:1). Reserved for BRAND + RUNNING states only.
 *   · Amber    #FFDDB8 / on #4A2B00 — the "partial grant" container family,
 *     so lime never doubles as PARTIAL (one hue, one meaning).
 *   · Filled error #B3261E / #FFFFFF — the critical-gate voice: AWAITING
 *     steps and confirm buttons are the loudest error moments.
 *   · Surfaces are tonal (violet-cast), never gray: #FBF9FF → #DFDBF0 (5 steps).
 *     Elevation = tone steps, not shadows.
 *   · Dark scheme: same hues re-toned (#C5C8FF primary on #11121D).
 *
 * The legacy OpenDroid token names (accentNeonGreen, accentCyan, …) are kept as
 * semantic bridges so the whole existing UI retints instantly; the Aurora names
 * are the canonical tokens all new/updated code should use.
 */
data class OpenDroidColors(
    // ── Aurora canonical tokens ─────────────────────────────
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    /** The surprise accent — fill-only, RUNNING + brand moments. */
    val lime: Color,
    val onLime: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    /** Partial-grant container family (amber), never doubles as lime. */
    val amberContainer: Color,
    val onAmberContainer: Color,
    /** Tonal 5-step surface ladder — elevation is tone, not shadow. */
    val background: Color,
    val surface: Color,
    val surfaceLow: Color,
    val surfaceHigh: Color,
    val surfaceHighest: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    /** Strong outline (chips' dashed pair, record bar). */
    val outline: Color,
    /** Hairline outline — borders, dividers, quiet chips. */
    val outlineVariant: Color,
    /** Onboarding night sky (fixed in both themes, per prototype). */
    val onboardingBackground: Color,
    val onboardingInk: Color,
    val onboardingSub: Color,
    /** Blob gradient stops (onboarding hero + chat avatars). */
    val blobGradient: List<Color>,

    // ── Legacy bridge tokens (semantic mapping to Aurora) ───
    /** Success / verified marks → Aurora teal. */
    val accentNeonGreen: Color,
    /** Filled button surfaces → Aurora primary. */
    val accentGreenButton: Color,
    /** Brand accent → Aurora primary. */
    val accentPurple: Color,
    /** Workhorse accent → Aurora teal. */
    val accentCyan: Color,
    val accentRed: Color,
    /** Warm warning ink → Aurora amber family. */
    val accentOrange: Color,
    val isDark: Boolean
) {
    /** Legacy alias — pre-Aurora screens read `.borderColor`. */
    val borderColor: Color get() = outlineVariant
    /** Legacy alias — cards sit on the tonal surface in Aurora. */
    val cardBackground: Color get() = surface
}

// ── Light palette: Aurora violet-cast tonal scheme ─────────────
val LightPalette = OpenDroidColors(
    primary = Color(0xFF3F3FD1),          // vivid blue-violet
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE4E1FF),
    onPrimaryContainer = Color(0xFF0D0D63),
    secondary = Color(0xFF56568F),
    secondaryContainer = Color(0xFFE2E1F3),
    onSecondaryContainer = Color(0xFF191945),
    tertiary = Color(0xFF00695B),         // teal
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF9CF0DC),
    onTertiaryContainer = Color(0xFF003830),
    lime = Color(0xFFC9F16F),             // the surprise — fill-only
    onLime = Color(0xFF253200),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    amberContainer = Color(0xFFFFDDB8),
    onAmberContainer = Color(0xFF4A2B00),
    background = Color(0xFFFBF9FF),       // tonal 5-step ladder, violet-cast
    surface = Color(0xFFF3F0FB),
    surfaceLow = Color(0xFFEDE9F8),
    surfaceHigh = Color(0xFFE6E2F4),
    surfaceHighest = Color(0xFFDFDBF0),
    textPrimary = Color(0xFF1B1B2F),      // ink
    textSecondary = Color(0xFF484858),    // ink-2
    outline = Color(0xFF75758A),
    outlineVariant = Color(0xFFCBC8DB),
    onboardingBackground = Color(0xFF101033),
    onboardingInk = Color(0xFFF0EEFF),
    onboardingSub = Color(0xFFC9C5EE),
    blobGradient = listOf(Color(0xFF6E63FF), Color(0xFF3F3FD1), Color(0xFF3F3FD1), Color(0xFF00A896)),

    // bridges
    accentNeonGreen = Color(0xFF00695B),  // teal: success marks readable as text
    accentGreenButton = Color(0xFF3F3FD1),// primary: filled buttons
    accentPurple = Color(0xFF3F3FD1),
    accentCyan = Color(0xFF00695B),
    accentRed = Color(0xFFB3261E),
    accentOrange = Color(0xFF8A5200),     // amber ink on light
    isDark = false
)

// ── Dark palette: same hues re-toned ───────────────────────────
val DarkPalette = OpenDroidColors(
    primary = Color(0xFFC5C8FF),
    onPrimary = Color(0xFF12127A),
    primaryContainer = Color(0xFF3A3AAE),
    onPrimaryContainer = Color(0xFFE3E2FF),
    secondary = Color(0xFFC3C3DD),
    secondaryContainer = Color(0xFF45456B),
    onSecondaryContainer = Color(0xFFE4E3F7),
    tertiary = Color(0xFF5EDCC1),
    onTertiary = Color(0xFF003830),
    tertiaryContainer = Color(0xFF0A5449),
    onTertiaryContainer = Color(0xFFA9F2E2),
    lime = Color(0xFFD6F884),
    onLime = Color(0xFF243200),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF410E0B),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    amberContainer = Color(0xFF5C4300),
    onAmberContainer = Color(0xFFFFDFA8),
    background = Color(0xFF11121D),
    surface = Color(0xFF181927),
    surfaceLow = Color(0xFF1D1E2E),
    surfaceHigh = Color(0xFF242537),
    surfaceHighest = Color(0xFF2B2C40),
    textPrimary = Color(0xFFE4E1F0),
    textSecondary = Color(0xFFC6C4D6),
    outline = Color(0xFF90909E),
    outlineVariant = Color(0xFF3D3E4F),
    onboardingBackground = Color(0xFF101033),
    onboardingInk = Color(0xFFF0EEFF),
    onboardingSub = Color(0xFFC9C5EE),
    blobGradient = listOf(Color(0xFF6E63FF), Color(0xFF3F3FD1), Color(0xFF3F3FD1), Color(0xFF00A896)),

    // bridges
    accentNeonGreen = Color(0xFF5EDCC1),
    accentGreenButton = Color(0xFFC5C8FF),
    accentPurple = Color(0xFFC5C8FF),
    accentCyan = Color(0xFF5EDCC1),
    accentRed = Color(0xFFFFB4AB),
    accentOrange = Color(0xFFFFDFA8),
    isDark = true
)

val LocalOpenDroidColors = staticCompositionLocalOf { DarkPalette }

/** Access the active palette from any @Composable */
object AppTheme {
    val colors: OpenDroidColors
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenDroidColors.current
}

// ── Dynamic Composable top-level aliases ────────────────────
// These allow all existing screens and composables to automatically
// adapt to Light / Dark theme dynamically without hardcoding palettes.

val DarkBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.background

val DarkSurface: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.surface

val CardBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.surface

val BorderColor: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.outlineVariant

val TextPrimary: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.textPrimary

val TextSecondary: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.textSecondary

val AccentNeonGreen: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.accentNeonGreen

val AccentGreenButton: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.accentGreenButton

val AccentPurple: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.accentPurple

val AccentCyan: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.accentCyan

val AccentRed: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.accentRed

val AccentOrange: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.accentOrange

// ── Aurora canonical aliases ────────────────────────────────

val AuroraPrimary: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.primary

val AuroraOnPrimary: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.onPrimary

val AuroraPrimaryContainer: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.primaryContainer

val AuroraOnPrimaryContainer: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.onPrimaryContainer

val AuroraTertiary: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.tertiary

val AuroraTertiaryContainer: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.tertiaryContainer

val AuroraOnTertiaryContainer: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.onTertiaryContainer

val AuroraLime: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.lime

val AuroraOnLime: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.onLime

val AuroraErrorContainer: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.errorContainer

val AuroraOnErrorContainer: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.onErrorContainer

val AuroraAmberContainer: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.amberContainer

val AuroraOnAmberContainer: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.onAmberContainer

val AuroraSurfaceLow: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.surfaceLow

val AuroraSurfaceHigh: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.surfaceHigh

val AuroraSurfaceHighest: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.surfaceHighest

val AuroraOutline: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.outline
