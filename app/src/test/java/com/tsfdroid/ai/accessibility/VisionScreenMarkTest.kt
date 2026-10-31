package com.tsfdroid.ai.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Milestone 1 (EGM v2.0.0 arc) — Set-of-Mark model tests (spec File 10,
 * Checkpoint 1). Pure JVM: the model and renderer carry no Android imports.
 */
class VisionScreenMarkTest {

    private fun mark(
        index: Int = 1,
        kind: MarkKind = MarkKind.BUTTON,
        label: String? = "Search",
        left: Int = 120,
        top: Int = 450,
        right: Int = 340,
        bottom: Int = 520
    ) = VisionScreenMark(index, kind, label, left, top, right, bottom)

    @Test
    fun `render emptyList isEmpty`() {
        assertEquals("", SetOfMarkRenderer.render(emptyList()))
    }

    @Test
    fun `render formatsIndexKindLabelBounds`() {
        val rendered = SetOfMarkRenderer.render(
            listOf(mark(index = 1, kind = MarkKind.BUTTON, label = "Search"))
        )
        assertEquals("""[1] Button "Search" (120,450..340,520)""", rendered)
    }

    @Test
    fun `render nullLabel usesUnlabeledPlaceholder`() {
        val rendered = SetOfMarkRenderer.render(
            listOf(mark(index = 2, kind = MarkKind.BUTTON, label = null))
        )
        assertEquals("""[2] Button "(unlabeled)" (120,450..340,520)""", rendered)
    }

    @Test
    fun `centerFractions areBoundsMidpointOverScreen`() {
        // bounds 120..340 on width 1080 → 230.0/1080; 450..520 on height 2400 → 485.0/2400
        val m = mark(left = 120, top = 450, right = 340, bottom = 520)
        assertEquals(230.0 / 1080, m.centerXFrac(1080), 1e-9)
        assertEquals(485.0 / 2400, m.centerYFrac(2400), 1e-9)
    }

    @Test
    fun `render multipleMarks oneLinePerMark`() {
        val rendered = SetOfMarkRenderer.render(
            listOf(
                mark(index = 1, kind = MarkKind.TEXT_FIELD, label = "Username", 0, 100, 500, 200),
                mark(index = 2, kind = MarkKind.OTHER, label = null, 0, 300, 500, 400)
            )
        )
        val lines = rendered.lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("[1] "))
        assertTrue(lines[1].startsWith("[2] "))
    }
}
