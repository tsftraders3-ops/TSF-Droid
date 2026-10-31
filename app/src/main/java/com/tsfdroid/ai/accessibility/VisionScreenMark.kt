package com.tsfdroid.ai.accessibility

/**
 * Milestone 1 (EGM v2.0.0 arc) — Set-of-Mark grounding model.
 *
 * One numbered mark per interactive accessibility element, collected in
 * depth-first walk order by
 * [AccessibilityNodeTraversal.interactiveMarks]. The numbered list lets a
 * TEXT-ONLY model pick `[3] Button "Search"` deterministically — no pixels,
 * no coordinate guessing — while [VisionScreenMark.centerXFrac] /
 * [VisionScreenMark.centerYFrac] still resolve the chosen mark to the
 * gesture coordinates the loop needs.
 *
 * Pure Kotlin: no Android imports, fully unit-testable on the JVM.
 */
enum class MarkKind { BUTTON, TEXT_FIELD, CHECKBOX, SWITCH, SPINNER, SCROLLABLE, OTHER }

data class VisionScreenMark(
    val index: Int,       // 1-based, assigned in walk order
    val kind: MarkKind,
    val label: String?,   // text ?: contentDescription, trimmed; null when both blank
    val left: Int, val top: Int, val right: Int, val bottom: Int  // getBoundsInScreen px
) {
    /** Mark center as a fraction of the screen width (0.0..1.0, top-left origin). */
    fun centerXFrac(screenWidth: Int): Double = ((left + right) / 2.0) / screenWidth

    /** Mark center as a fraction of the screen height (0.0..1.0, top-left origin). */
    fun centerYFrac(screenHeight: Int): Double = ((top + bottom) / 2.0) / screenHeight
}

/**
 * Renders the numbered mark list handed to the locator prompt — one line per
 * mark, e.g. `[1] Button "Search" (120,450..340,520)`.
 *
 * NOTE: the `(unlabeled)` placeholder rendered for a null label is a
 * DIFFERENT string from VisionDrivingLoop's `"(unlabeled target)"` consent
 * sentinel — do not unify them; the sentinel is a consent payload, this is
 * a locator display affordance.
 */
object SetOfMarkRenderer {

    private val KIND_DISPLAY = mapOf(
        MarkKind.BUTTON to "Button",
        MarkKind.TEXT_FIELD to "Text field",
        MarkKind.CHECKBOX to "Checkbox",
        MarkKind.SWITCH to "Switch",
        MarkKind.SPINNER to "Spinner",
        MarkKind.SCROLLABLE to "Scrollable",
        MarkKind.OTHER to "Other"
    )

    /** One line per mark; empty list → "". */
    fun render(marks: List<VisionScreenMark>): String =
        marks.joinToString("\n") { mark ->
            val kind = KIND_DISPLAY[mark.kind] ?: mark.kind.name
            "[${mark.index}] $kind \"${mark.label ?: "(unlabeled)"}\" (${mark.left},${mark.top}..${mark.right},${mark.bottom})"
        }
}
