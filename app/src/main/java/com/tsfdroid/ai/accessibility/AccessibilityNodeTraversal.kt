package com.tsfdroid.ai.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import javax.inject.Inject

/**
 * Pure node-tree traversal/matching logic used by [OpenDroidAccessibilityService],
 * extracted so it can be exercised in tests without a bound accessibility service.
 *
 * Every function here takes the root [AccessibilityNodeInfo] as a parameter instead
 * of reading `rootInActiveWindow` itself, so a test can hand it either the real
 * service's root or a root obtained straight from `UiAutomation` - same behavior,
 * no service required. See #66 (prototype) / #105 (this extraction).
 *
 * Stateless and dependency-free: production wires it in through Hilt, tests can
 * just `AccessibilityNodeTraversal()` it directly.
 *
 * Callers remain responsible for recycling the root node they obtained; this class
 * only recycles the intermediate/child nodes it creates while walking.
 */
class AccessibilityNodeTraversal @Inject constructor() {

    /** Concatenates the text/contentDescription of every node in the tree, depth-first. */
    fun screenText(root: AccessibilityNodeInfo?): String {
        if (root == null) return ""
        val sb = StringBuilder()
        collectText(root, sb)
        return sb.toString()
    }

    private fun collectText(node: AccessibilityNodeInfo, sb: StringBuilder) {
        val nodeText = node.text?.toString()
        val contentDesc = node.contentDescription?.toString()

        if (!nodeText.isNullOrEmpty()) {
            sb.append(nodeText).append("\n")
        } else if (!contentDesc.isNullOrEmpty()) {
            sb.append(contentDesc).append("\n")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, sb)
            child.recycle()
        }
    }

    /**
     * Finds the first node matching [text] (or the nearest clickable ancestor,
     * for leaves that aren't themselves clickable) and performs ACTION_CLICK on it.
     * Returns whether a click was dispatched.
     */
    fun findAndClick(root: AccessibilityNodeInfo?, text: String): Boolean {
        if (root == null) return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node.recycle()
                return true
            }
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) {
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    parent.recycle()
                    node.recycle()
                    return true
                }
                parent = parent.parent
            }
            node.recycle()
        }
        return false
    }

    /**
     * Walks the tree depth-first for a clickable node whose text or
     * contentDescription (case-insensitive) is one of [labels], and clicks it.
     * This is the IME-submit fallback used when ACTION_IME_ENTER isn't
     * available or fails.
     */
    fun findAndClickSubmitControl(root: AccessibilityNodeInfo?, labels: List<String>): Boolean {
        if (root == null) return false
        return findSubmitNode(root, labels)
    }

    private fun findSubmitNode(node: AccessibilityNodeInfo, labels: List<String>): Boolean {
        val text = node.text?.toString()?.lowercase()
        val contentDesc = node.contentDescription?.toString()?.lowercase()
        if (node.isClickable && (labels.contains(text) || labels.contains(contentDesc))) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findSubmitNode(child, labels)) {
                child.recycle()
                return true
            }
            child.recycle()
        }
        return false
    }

    /**
     * Milestone 1 (EGM v2.0.0 arc) — Set-of-Mark collection: a depth-first
     * walk that numbers every interactive, on-screen element into a
     * [VisionScreenMark].
     *
     * A node becomes a mark when it `isClickable || isEditable ||
     * isScrollable` AND its [AccessibilityNodeInfo.getBoundsInScreen] rect
     * has positive width and height (off-screen / zero-size nodes are noise,
     * not marks). The walk aborts as soon as [maxMarks] marks are collected
     * — node visits are bounded by the walk terminating, not by tree size.
     *
     * Kind resolution (evaluated in order): `isEditable → TEXT_FIELD`;
     * `isCheckable → CHECKBOX`; `isScrollable → SCROLLABLE`; className
     * ending `Spinner → SPINNER`; ending `Switch → SWITCH`; ending
     * `Button → BUTTON`; else `OTHER`. Label = `text ?: contentDescription`,
     * trimmed, null when blank.
     *
     * Same recycling contract as the other walkers: intermediate child nodes
     * are recycled here; the caller recycles the root.
     */
    fun interactiveMarks(root: AccessibilityNodeInfo?, maxMarks: Int = 25): List<VisionScreenMark> {
        if (root == null) return emptyList()
        val marks = mutableListOf<VisionScreenMark>()
        collectInteractiveMarks(root, maxMarks, marks)
        return marks
    }

    private fun collectInteractiveMarks(
        node: AccessibilityNodeInfo,
        maxMarks: Int,
        marks: MutableList<VisionScreenMark>
    ) {
        if (marks.size >= maxMarks) return
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val interactive = node.isClickable || node.isEditable || node.isScrollable
        if (interactive && rect.width() > 0 && rect.height() > 0) {
            marks += VisionScreenMark(
                index = marks.size + 1,
                kind = resolveMarkKind(node),
                label = (node.text?.toString() ?: node.contentDescription?.toString())
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() },
                left = rect.left,
                top = rect.top,
                right = rect.right,
                bottom = rect.bottom
            )
        }
        for (i in 0 until node.childCount) {
            if (marks.size >= maxMarks) break
            val child = node.getChild(i) ?: continue
            collectInteractiveMarks(child, maxMarks, marks)
            child.recycle()
        }
    }

    private fun resolveMarkKind(node: AccessibilityNodeInfo): MarkKind {
        if (node.isEditable) return MarkKind.TEXT_FIELD
        if (node.isCheckable) return MarkKind.CHECKBOX
        if (node.isScrollable) return MarkKind.SCROLLABLE
        val className = node.className?.toString() ?: return MarkKind.OTHER
        if (className.endsWith("Spinner")) return MarkKind.SPINNER
        if (className.endsWith("Switch")) return MarkKind.SWITCH
        if (className.endsWith("Button")) return MarkKind.BUTTON
        return MarkKind.OTHER
    }
}
