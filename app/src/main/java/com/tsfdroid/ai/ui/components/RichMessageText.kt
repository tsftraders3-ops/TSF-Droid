package com.tsfdroid.ai.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tsfdroid.ai.ui.text.Block
import com.tsfdroid.ai.ui.text.Span
import com.tsfdroid.ai.ui.text.parseMarkdownLite
import com.tsfdroid.ai.ui.theme.AccentCyan
import com.tsfdroid.ai.ui.theme.AuroraSurfaceHigh
import com.tsfdroid.ai.ui.theme.TextSecondary

/**
 * v1.3.0 (Phase 14, Wave B): the rich agent-reply renderer.
 *
 * Replaces the old plain `Text(message.text)` for agent bubbles so researched
 * answers (headings, bullets, fenced code, URLs) read like the Claude / Gemini
 * apps instead of "text slop". Markdown-lite is parsed once per message
 * (see MarkdownLite.kt) and each block is laid out with Aurora styling.
 *
 * A11y contract: every character of the original message is rendered through
 * real [Text] composables (AnnotatedString semantics expose the full text),
 * so UIAutomator text search keeps finding the message body. Inline link
 * ranges are tappable via LinkAnnotation and open the browser.
 */
@Composable
fun RichMessageText(text: String, baseColor: Color, modifier: Modifier = Modifier) {
    if (text.isEmpty()) return
    val blocks = remember(text) { parseMarkdownLite(text) }
    val linkColor = AccentCyan

    Column(modifier = modifier) {
        blocks.forEachIndexed { index, block ->
            val topPadding = if (index == 0) 0.dp else blockGap(block)
            when (block) {
                is Block.Heading -> Text(
                    text = annotated(block.spans, baseColor, linkColor),
                    fontSize = if (block.level <= 2) 15.sp else 14.sp,
                    lineHeight = if (block.level <= 2) 21.sp else 20.sp,
                    color = baseColor,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(top = if (index == 0) 4.dp else topPadding)
                        .padding(bottom = 4.dp)
                )

                is Block.Paragraph -> Text(
                    text = annotated(block.spans, baseColor, linkColor),
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    color = baseColor,
                    modifier = Modifier.padding(top = topPadding)
                )

                is Block.Bullet -> Column(
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.padding(top = topPadding)
                ) {
                    block.items.forEach { item ->
                        Row {
                            Text(
                                text = "•",
                                fontSize = 14.sp,
                                lineHeight = 21.sp,
                                color = AccentCyan
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = annotated(item, baseColor, linkColor),
                                fontSize = 14.sp,
                                lineHeight = 21.sp,
                                color = baseColor,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                is Block.Numbered -> Column(
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.padding(top = topPadding)
                ) {
                    block.items.forEachIndexed { itemIndex, item ->
                        Row {
                            Text(
                                text = "${itemIndex + 1}.",
                                fontSize = 14.sp,
                                lineHeight = 21.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = AccentCyan
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = annotated(item, baseColor, linkColor),
                                fontSize = 14.sp,
                                lineHeight = 21.sp,
                                color = baseColor,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                is Block.CodeBlock -> Row(
                    modifier = Modifier
                        .padding(top = topPadding)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(baseColor.copy(alpha = 0.08f))
                ) {
                    Text(
                        text = block.content,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = baseColor.copy(alpha = 0.92f),
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 10.dp)
                    )
                }

                is Block.Quote -> Row(
                    modifier = Modifier
                        .padding(top = topPadding)
                        .height(IntrinsicSize.Min)
                ) {
                    // Left accent border: 2.dp strip, 3.dp gutter, muted text.
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(AccentCyan)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = annotated(block.spans, baseColor, linkColor),
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                        color = baseColor.copy(alpha = 0.75f),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/**
 * v1.3.0: numbered SOURCES chip row under agent replies that cite URLs —
 * the Claude / Gemini research-answer pattern. "[1] domain.com" chips,
 * horizontally scrollable, tappable (opens the browser).
 */
@Composable
fun SourceChipsRow(urls: List<String>, modifier: Modifier = Modifier) {
    if (urls.isEmpty()) return
    val context = LocalContext.current
    // The component itself caps at 8 chips (the Claude / Gemini pattern) so it
    // stays correct no matter what a caller passes.
    val cappedUrls = remember(urls) { urls.take(8) }

    Column(modifier = modifier) {
        Text(
            text = "SOURCES",
            fontSize = 9.sp,
            color = TextSecondary,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            cappedUrls.forEachIndexed { index, url ->
                val host = remember(url) { hostOf(url) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(AuroraSurfaceHigh)
                        .clickable { openUrl(context, url) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "[${index + 1}] $host",
                        color = AccentCyan,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * Inter-block top padding for every block after the first. Headings get a
 * little extra air above (they introduce a new section), code blocks a touch
 * more (they read best with clear separation).
 */
private fun blockGap(block: Block): Dp = when (block) {
    is Block.Heading -> 8.dp
    is Block.CodeBlock -> 6.dp
    else -> 4.dp
}

/**
 * Renders parsed inline [Span]s into one [AnnotatedString] so a paragraph is a
 * single wrapping [Text] (correct line breaking, one semantics node, full text
 * exposed to the accessibility tree). Links become tappable ranges that open
 * the browser; code runs get a monospace family and a soft plate background.
 */
@Composable
private fun annotated(
    spans: List<Span>,
    baseColor: Color,
    linkColor: Color
): AnnotatedString {
    val context = LocalContext.current
    return buildAnnotatedString {
        spans.forEach { span ->
            val style = SpanStyle(
                color = if (span.linkUrl != null) linkColor else Color.Unspecified,
                fontWeight = when {
                    span.bold -> FontWeight.Bold
                    span.linkUrl != null -> FontWeight.SemiBold
                    else -> null
                },
                fontStyle = if (span.italic) FontStyle.Italic else null,
                fontFamily = if (span.code) FontFamily.Monospace else null,
                background = if (span.code) baseColor.copy(alpha = 0.10f) else Color.Unspecified
            )
            val url = span.linkUrl
            if (url != null) {
                withLink(
                    LinkAnnotation.Clickable(
                        tag = url,
                        styles = TextLinkStyles(style = style)
                    ) { openUrl(context, url) }
                ) {
                    append(span.text)
                }
            } else {
                withStyle(style) {
                    append(span.text)
                }
            }
        }
    }
}

/** Opens [url] in the browser; any failure (no handler, dead activity) is swallowed. */
private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

/** "[https://example.com/path]" → "example.com"; falls back to a trimmed string. */
private fun hostOf(url: String): String {
    runCatching { Uri.parse(url).host }.getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }
    // Uri.parse rejected it (exotic scheme / malformed) — strip the scheme by
    // hand so the chip still shows something recognizable.
    return url.substringAfter("://", url).substringBefore('/').ifBlank { url }
}
