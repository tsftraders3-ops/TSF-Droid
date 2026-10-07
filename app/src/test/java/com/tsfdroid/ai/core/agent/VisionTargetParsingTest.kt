package com.tsfdroid.ai.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M-02 remediation (audit fc9ea97): the vision path must ground a screenshot
 * target into a STRUCTURED, machine-validated result — not prose. The wire
 * contract the locator prompt demands from the vision model:
 *
 *   {"target_found": <bool>, "x": <0.0..1.0>, "y": <0.0..1.0>, "confidence": <0.0..1.0>}
 *
 * x and y are FRACTIONS of the screen dimensions (the model sees an image,
 * not pixels); VisionEngine maps them to absolute pixels at dispatch time.
 * A tap is only eligible above the 0.85 confidence floor.
 */
class VisionTargetParsingTest {

    // ── Parse contract ─────────────────────────────────────────────────────

    @Test
    fun `clean JSON response parses into a located target`() {
        val target = VisionEngine.parseTargetResponse(
            """{"target_found": true, "x": 0.5, "y": 0.75, "confidence": 0.93}"""
        )
        assertTrue(target.targetFound)
        assertEquals(0.5, target.x!!, 1e-6f.toDouble())
        assertEquals(0.75, target.y!!, 1e-6f.toDouble())
        assertEquals(0.93f, target.confidence, 1e-6f)
    }

    @Test
    fun `markdown-fenced JSON still parses`() {
        val target = VisionEngine.parseTargetResponse(
            "```json\n{\"target_found\": true, \"x\": 0.25, \"y\": 0.125, \"confidence\": 0.9}\n```"
        )
        assertTrue(target.targetFound)
        assertEquals(0.25, target.x!!, 1e-6f.toDouble())
    }

    @Test
    fun `prose-wrapped JSON still parses`() {
        val target = VisionEngine.parseTargetResponse(
            "The target is visible. My answer: {\"target_found\": true, \"x\": 0.9, \"y\": 0.1, \"confidence\": 0.88} hope that helps"
        )
        assertTrue(target.targetFound)
        assertEquals(0.9, target.x!!, 1e-6f.toDouble())
    }

    @Test
    fun `target not found parses as not found`() {
        val target = VisionEngine.parseTargetResponse(
            """{"target_found": false, "x": 0, "y": 0, "confidence": 0.4}"""
        )
        assertFalse(target.targetFound)
        assertFalse(VisionEngine.isTapEligible(target))
    }

    @Test
    fun `malformed response never produces a target`() {
        assertFalse(VisionEngine.parseTargetResponse("I see a login button").targetFound)
        assertFalse(VisionEngine.parseTargetResponse("").targetFound)
        assertFalse(VisionEngine.parseTargetResponse("{broken json").targetFound)
        assertFalse(VisionEngine.parseTargetResponse("null").targetFound)
    }

    @Test
    fun `missing coordinates are never eligible even when found is true`() {
        val target = VisionEngine.parseTargetResponse(
            """{"target_found": true, "confidence": 0.99}"""
        )
        assertTrue(target.targetFound)
        assertFalse(VisionEngine.isTapEligible(target))
    }

    // ── Eligibility gate ───────────────────────────────────────────────────

    @Test
    fun `confidence above 085 is eligible`() {
        listOf(0.86, 0.9, 1.0).forEach { confidence ->
            val target = VisionEngine.parseTargetResponse(
                """{"target_found": true, "x": 0.5, "y": 0.5, "confidence": $confidence}"""
            )
            assertTrue("confidence $confidence must be eligible", VisionEngine.isTapEligible(target))
        }
    }

    @Test
    fun `confidence at or below 085 is NOT eligible`() {
        listOf(0.0, 0.5, 0.84, 0.85).forEach { confidence ->
            val target = VisionEngine.parseTargetResponse(
                """{"target_found": true, "x": 0.5, "y": 0.5, "confidence": $confidence}"""
            )
            assertFalse("confidence $confidence must be refused", VisionEngine.isTapEligible(target))
        }
    }

    @Test
    fun `out-of-range fractions are not eligible`() {
        listOf(
            """{"target_found": true, "x": -0.1, "y": 0.5, "confidence": 0.95}""",
            """{"target_found": true, "x": 0.5, "y": 1.5, "confidence": 0.95}""",
            """{"target_found": true, "x": 2.0, "y": 0.5, "confidence": 0.95}"""
        ).forEach { json ->
            val target = VisionEngine.parseTargetResponse(json)
            assertFalse("out-of-range target must be refused: $json", VisionEngine.isTapEligible(target))
        }
    }

    @Test
    fun `in-range fractions with high confidence are eligible`() {
        val target = VisionEngine.parseTargetResponse(
            """{"target_found": true, "x": 0.0, "y": 1.0, "confidence": 0.99}"""
        )
        assertTrue(VisionEngine.isTapEligible(target))
    }
}
