@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.tsfdroid.ai.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.tsfdroid.ai.R

/**
 * Aurora type system (prototype-a-aurora.html):
 *   · Display: Bricolage Grotesque — an expressive humanist grotesque with ink
 *     traps; gives the Google-native warmth personality at display sizes.
 *   · Body/UI: Inter — Bricolage's quirk would cost legibility at 13–15px in
 *     dense lists; Inter carries UI. Roboto/system fallbacks keep an offline
 *     load M3-native.
 *   · Scale: 44/28/20/15/13/12 — display reserved for the Chat greeting,
 *     Onboarding hero, and one hero figure per screen.
 *
 * Both families ship as variable fonts; each weight instantiates its `wght`
 * axis explicitly (API 26+, matching minSdk).
 */
val BricolageGrotesque = FontFamily(
    Font(
        R.font.bricolage_grotesque,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.Axis("wght", 400))
    ),
    Font(
        R.font.bricolage_grotesque,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.Axis("wght", 500))
    ),
    Font(
        R.font.bricolage_grotesque,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.Axis("wght", 600))
    ),
    Font(
        R.font.bricolage_grotesque,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.Axis("wght", 700))
    ),
    Font(
        R.font.bricolage_grotesque,
        weight = FontWeight.ExtraBold,
        variationSettings = FontVariation.Settings(FontVariation.Axis("wght", 800))
    )
)

val Inter = FontFamily(
    Font(
        R.font.inter_variable,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.Axis("wght", 400))
    ),
    Font(
        R.font.inter_variable,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.Axis("wght", 500))
    ),
    Font(
        R.font.inter_variable,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.Axis("wght", 600))
    ),
    Font(
        R.font.inter_variable,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.Axis("wght", 700))
    )
)

/**
 * Extra display styles used by the prototype's hero moments (not part of the
 * M3 default roles): the 52px onboarding title, 38px chat greeting / screen
 * big titles at 31px, and the 34px routine confidence figure.
 */
object AuroraType {
    /** Onboarding hero — "Your phone. Your rules. Your AI." */
    val onboardingTitle = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 44.sp,
        lineHeight = 46.sp,
        letterSpacing = (-1.2).sp
    )

    /** Chat greeting + screen big titles (38px in the prototype). */
    val bigTitle = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 34.sp,
        lineHeight = 35.sp,
        letterSpacing = (-0.7).sp
    )

    /** Screen big titles on content-dense pages (31px in the prototype). */
    val bigTitle31 = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 31.sp,
        lineHeight = 33.sp,
        letterSpacing = (-0.7).sp
    )

    /** Hero figures — routine confidence (34px), stat tiles (26px), tier counts (21px). */
    val heroFigure = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 34.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.9).sp
    )

    val statFigure = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.5).sp
    )

    val countFigure = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.Bold,
        fontSize = 21.sp,
        lineHeight = 22.sp
    )
}

/**
 * Material role mapping: display/headline/title styles carry Bricolage;
 * body/label styles carry Inter. Every Text without an explicit style
 * inherits these automatically.
 */
val Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 44.sp,
        lineHeight = 46.sp,
        letterSpacing = (-1.0).sp
    ),
    displayMedium = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 38.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.8).sp
    ),
    displaySmall = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 31.sp,
        lineHeight = 33.sp,
        letterSpacing = (-0.7).sp
    ),
    headlineLarge = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.4).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp
    ),
    titleLarge = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 25.sp,
        letterSpacing = (-0.3).sp
    ),
    titleMedium = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    titleSmall = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 23.sp,
        letterSpacing = 0.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp
    ),
    bodySmall = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp
    ),
    labelLarge = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.5.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp
    ),
    labelMedium = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.5.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.5.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.sp
    )
)
