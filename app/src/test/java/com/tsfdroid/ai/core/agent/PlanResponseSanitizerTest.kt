package com.tsfdroid.ai.core.agent

import com.tsfdroid.ai.data.models.PlanStep
import com.tsfdroid.ai.data.models.StepStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the v1.0.4 plan-answer hardening: reasoning blocks stripped,
 * balanced JSON recovered from prose, and prose replies classified into the
 * CHAT / ASK_USER action protocol instead of failing the planning turn.
 *
 * The fixtures mirror real free-tier shapes observed on
 * mimo-v2.6-flash-free (reasoning deltas + narrated JSON) during the
 * v1.0.3 field failures.
 */
class PlanResponseSanitizerTest {

    // --- stripReasoningBlocks ---

    @Test
    fun `closed think blocks are removed wherever they appear`() {
        val raw = "<think>Let me plan. User wants YouTube.</think>{\"goal\":\"play\"}"
        assertEquals("{\"goal\":\"play\"}", PlanResponseSanitizer.stripReasoningBlocks(raw))
    }

    @Test
    fun `reasoning before and after the plan is stripped`() {
        val raw = "<think>a</think>{\"goal\":\"x\"}<think>double check</think>"
        assertEquals("{\"goal\":\"x\"}", PlanResponseSanitizer.stripReasoningBlocks(raw))
    }

    @Test
    fun `unterminated think block drops everything from the tag on`() {
        val raw = "{\"goal\":\"keep\"}\n<think>stream cut mid-thought"
        assertEquals("{\"goal\":\"keep\"}", PlanResponseSanitizer.stripReasoningBlocks(raw))
    }

    @Test
    fun `reasoning tag variant is also stripped`() {
        val raw = "<reasoning>why</reasoning>{\"goal\":\"y\"}"
        assertEquals("{\"goal\":\"y\"}", PlanResponseSanitizer.stripReasoningBlocks(raw))
    }

    @Test
    fun `text without thinking passes through`() {
        assertEquals("{\"goal\":\"z\"}", PlanResponseSanitizer.stripReasoningBlocks("{\"goal\":\"z\"}"))
    }

    // --- extractFirstJsonObject ---

    @Test
    fun `plan narrated before prose is recovered`() {
        val raw = "Sure, here is the plan to open YouTube:\n{\"goal\":\"play\",\"steps\":[{\"stepId\":\"s1\"}]}"
        assertEquals(
            "{\"goal\":\"play\",\"steps\":[{\"stepId\":\"s1\"}]}",
            PlanResponseSanitizer.extractFirstJsonObject(raw)
        )
    }

    @Test
    fun `braces inside string literals do not break the depth scan`() {
        val raw = "prefix {\"a\":\"curly } brace\",\"b\":{\"c\":\"\\\\\"}} suffix"
        assertEquals(
            "{\"a\":\"curly } brace\",\"b\":{\"c\":\"\\\\\"}}",
            PlanResponseSanitizer.extractFirstJsonObject(raw)
        )
    }

    @Test
    fun `trailing prose after the object is not included`() {
        val raw = "{\"a\":1} Hope that helps!"
        assertEquals("{\"a\":1}", PlanResponseSanitizer.extractFirstJsonObject(raw))
    }

    @Test
    fun `unbalanced object returns null`() {
        assertNull(PlanResponseSanitizer.extractFirstJsonObject("speech { without end"))
        assertNull(PlanResponseSanitizer.extractFirstJsonObject("no braces at all"))
    }

    @Test
    fun `first object wins when two are present`() {
        val raw = "{\"first\":true} then {\"second\":true}"
        assertEquals("{\"first\":true}", PlanResponseSanitizer.extractFirstJsonObject(raw))
    }

    // --- classifyProseReply ---

    @Test
    fun `clarifying question routes to ASK_USER`() {
        val (action, params) = PlanResponseSanitizer.classifyProseReply(
            "Sure — can call someone if you tell me the name. Who should I call?"
        )!!
        assertEquals("ASK_USER", action)
        assertTrue(params["question"]!!.endsWith("Who should I call?"))
    }

    @Test
    fun `conversational answer routes to CHAT`() {
        val (action, params) = PlanResponseSanitizer.classifyProseReply(
            "I can open YouTube for you whenever you like."
        )!!
        assertEquals("CHAT", action)
        assertTrue(params["response"]!!.startsWith("I can open YouTube"))
    }

    @Test
    fun `json-shaped text is never classified as prose`() {
        assertNull(PlanResponseSanitizer.classifyProseReply("{\"broken\": true"))
        assertNull(PlanResponseSanitizer.classifyProseReply("[1, 2"))
    }

    @Test
    fun `blank text yields no classification`() {
        assertNull(PlanResponseSanitizer.classifyProseReply("   \n  "))
    }

    @Test
    fun `runaway prose is bounded to the 16k budget`() {
        val long = "word ".repeat(10_000)
        val (_, params) = PlanResponseSanitizer.classifyProseReply(long)!!
        assertTrue(params.values.first().length <= PlanResponseSanitizer.PROSE_MAX_CHARS)
    }

    @Test
    fun `v1_0_5 long real answers are no longer mangled to 400 chars`() {
        // The v1.0.4 field failure: a capability-audit answer reached the
        // dispatcher chopped mid-word ("…which ca"). Real answers up to a few
        // thousand chars must survive classification intact.
        val audit = buildString {
            append("I checked every capability in the list. ")
            repeat(60) { i -> append("Capability number $i works in this environment. ") }
            append("End of report, which ca") // > 400 chars total
        }
        val (_, params) = PlanResponseSanitizer.classifyProseReply(audit)!!
        val response = params["response"]!!
        assertTrue(response.length > 400)
        assertTrue(response.endsWith("which ca"))
        assertTrue(response.contains("Capability number 59"))
    }

    @Test
    fun `whitespace collapse keeps the answer readable without inflating it`() {
        val text = "Answer   with    many    spaces\n\nand newlines"
        val (_, params) = PlanResponseSanitizer.classifyProseReply(text)!!
        assertEquals("Answer with many spaces and newlines", params["response"])
    }

    // --- proseDeclinesAction (v1.0.5 deferral gate) ---

    @Test
    fun `short commitment against an artifact goal is a deferral`() {
        assertTrue(
            PlanResponseSanitizer.proseDeclinesAction(
                "I am creating the HTML file for you.",
                "Create a file at Documents/e2e_site.html with a heading Hello E2E"
            )
        )
    }

    @Test
    fun `lets build it against a website goal is a deferral`() {
        assertTrue(
            PlanResponseSanitizer.proseDeclinesAction(
                "Let's build it!",
                "build an HTML website for me"
            )
        )
    }

    @Test
    fun `long substantive answers are never treated as deferrals`() {
        val audit = "Here is the capability report you asked for. " + "Detail ".repeat(120)
        assertTrue(audit.length > 600)
        assertTrue(!PlanResponseSanitizer.proseDeclinesAction(audit, "make a report of your capabilities"))
    }

    @Test
    fun `short conversational answer to a non-artifact goal is not a deferral`() {
        assertTrue(
            !PlanResponseSanitizer.proseDeclinesAction(
                "OpenAI released a new model today.",
                "what is new in AI?"
            )
        )
    }

    @Test
    fun `blank response is never a deferral`() {
        assertTrue(!PlanResponseSanitizer.proseDeclinesAction("", "create a file"))
        assertTrue(!PlanResponseSanitizer.proseDeclinesAction(null, "create a file"))
    }

    // --- v1.0.6 data-goal deferral gate (the gold-price field failure) ---

    @Test
    fun `let me check against a price goal is a deferral`() {
        assertTrue(
            PlanResponseSanitizer.proseDeclinesAction(
                "Let me check the current gold price for you.",
                "csn u fetch the price of gold"
            )
        )
    }

    @Test
    fun `let me fetch against a search goal is a deferral`() {
        assertTrue(
            PlanResponseSanitizer.proseDeclinesAction(
                "Sure! Let me search for the latest iPhone price.",
                "search for latest iphone price"
            )
        )
    }

    @Test
    fun `substantive data answer to an explicit fetch command still defers to the search`() {
        // v1.3.0 round-7 contract change: an explicit lookup command ("fetch
        // the price of gold") IS the task — a memory answer, however
        // substantive-looking, dodges it and can serve stale data (run-102
        // cap22: "data I retrieved earlier in our conversation"). The
        // corrective ladder synthesizes the real WEB_SEARCH step instead.
        assertTrue(
            PlanResponseSanitizer.proseDeclinesAction(
                "Gold is trading around \$4,284 per ounce today, up 0.4%.",
                "csn u fetch the price of gold"
            )
        )
        // A NON-commanded data goal keeps the old exemption: the model's
        // substantive answer stands as delivered.
        assertTrue(
            !PlanResponseSanitizer.proseDeclinesAction(
                "Gold is trading around \$4,284 per ounce today, up 0.4%.",
                "what is gold trading at?"
            )
        )
    }

    @Test
    fun `goalWantsWebData matches price fetch search phrasing`() {
        assertTrue(PlanResponseSanitizer.goalWantsWebData("fetch the price of gold"))
        assertTrue(PlanResponseSanitizer.goalWantsWebData("latest iphone price"))
        assertTrue(!PlanResponseSanitizer.goalWantsWebData("tell me a joke"))
    }

    @Test
    fun `goalWantsArtifact matches file html pdf phrasing`() {
        assertTrue(PlanResponseSanitizer.goalWantsArtifact("create an award winning website in html"))
        assertTrue(PlanResponseSanitizer.goalWantsArtifact("make a pdf report of gold price"))
        assertTrue(!PlanResponseSanitizer.goalWantsArtifact("what is the capital of France"))
    }

    @Test
    fun `let me put together against a website goal is a deferral`() {
        assertTrue(
            PlanResponseSanitizer.proseDeclinesAction(
                "Love it! Let me put together something slick for you.",
                "can u create a award winning website in html"
            )
        )
    }

    // --- v1.0.6 loop-17: plan-shaped deferral gate ---

    @Test
    fun `a chat-only plan defers a data goal`() {
        assertTrue(
            PlanResponseSanitizer.planDefersGoal(
                listOf("CHAT"), "cna u fetch the price of gold now"
            )
        )
    }

    @Test
    fun `a chat-only plan defers an artifact goal`() {
        assertTrue(
            PlanResponseSanitizer.planDefersGoal(
                listOf("CHAT"), "can u create a award winning website in html"
            )
        )
    }

    @Test
    fun `a plan with a real data action does not defer a data goal`() {
        assertTrue(
            !PlanResponseSanitizer.planDefersGoal(
                listOf("WEB_SEARCH", "CREATE_PDF"), "create a pdf report of the gold price"
            )
        )
    }

    @Test
    fun `a plan with write_file does not defer an artifact goal`() {
        assertTrue(
            !PlanResponseSanitizer.planDefersGoal(
                listOf("WRITE_FILE"), "can u create a award winning website in html"
            )
        )
    }

    // --- v1.3.0 round-7: the run-102 regressions ---

    @Test
    fun `a 10k-char essay about the website is still a deferral`() {
        // Run-102, both passes: the corrective re-ask returned a 10,062-char
        // design essay instead of the HTML file; the old 600-char guard let
        // it through as a CHAT step and nothing was ever written.
        val essay = "An award-winning website needs a strong visual identity. " +
            "Let me walk you through the design. ".repeat(180)
        assertTrue(essay.length > 600)
        assertTrue(
            PlanResponseSanitizer.proseDeclinesAction(
                essay, "can u create a award winning website in html"
            )
        )
    }

    @Test
    fun `a long grounded memory answer against an explicit search command is a deferral`() {
        // Run-102 cap22 pass-2: "data I retrieved earlier in our conversation"
        // — the goal COMMANDS the search, so however good the remembered
        // data is, the prose dodges the commanded action.
        val memoryAnswer = "Bitcoin's current price is approximately \$83,476 USD. " +
            "Sources: - coindesk.com — https://www.coindesk.com/price/bitcoin/ " +
            "Note: This reflects the most recent data I retrieved earlier in our conversation."
        assertTrue(
            PlanResponseSanitizer.goalDemandsFreshData(
                "Search the web for the current Bitcoin price in USD and tell me the " +
                    "price, citing the exact source URL in your answer."
            )
        )
        assertTrue(
            PlanResponseSanitizer.proseDeclinesAction(
                memoryAnswer,
                "Search the web for the current Bitcoin price in USD and tell me the " +
                    "price, citing the exact source URL in your answer."
            )
        )
    }

    @Test
    fun `long prose against a pdf goal is a deferral at any length`() {
        val longProse = "Sure thing. ".repeat(150)
        assertTrue(longProse.length > 600)
        assertTrue(
            PlanResponseSanitizer.proseDeclinesAction(
                longProse, "create a pdf report about solar energy"
            )
        )
    }

    @Test
    fun `informational goals naming a format stay conversational`() {
        // Critic round-1 MUST-FIX 1: "explain what json is" / "how does
        // html work" are knowledge questions — the format word alone must
        // never force a file write at any prose length; a creation signal
        // is required for the concrete gate.
        val longAnswer = "JSON is a lightweight, text-based data interchange format. ".repeat(20)
        assertTrue(longAnswer.length > 600)
        assertTrue(!PlanResponseSanitizer.proseDeclinesAction(longAnswer, "explain what json is"))
        assertTrue(!PlanResponseSanitizer.proseDeclinesAction(longAnswer, "how does html work"))
        // The same formats WITH a creation signal defer at any length.
        assertTrue(
            PlanResponseSanitizer.proseDeclinesAction(longAnswer, "make me a powerpoint about cats")
        )
        assertTrue(
            PlanResponseSanitizer.proseDeclinesAction(longAnswer, "can u create a website in html")
        )
    }

    @Test
    fun `lookup commands with trailing punctuation still demand fresh data`() {
        // Critic round-1: the plain space-pad missed "search the web," and
        // "look it up!" — punctuation is normalized to a word edge now.
        assertTrue(
            PlanResponseSanitizer.goalDemandsFreshData("search the web, find the bitcoin price")
        )
        assertTrue(PlanResponseSanitizer.goalDemandsFreshData("please look it up!"))
    }

    // --- goalDemandsFreshData (round-7) ---

    @Test
    fun `explicit lookup commands demand fresh data`() {
        assertTrue(PlanResponseSanitizer.goalDemandsFreshData("Search the web for the Bitcoin price"))
        assertTrue(PlanResponseSanitizer.goalDemandsFreshData("google the capital of France"))
        assertTrue(PlanResponseSanitizer.goalDemandsFreshData("Can you look it up and tell me?"))
        assertTrue(PlanResponseSanitizer.goalDemandsFreshData("please fetch the page and summarize it"))
        assertTrue(PlanResponseSanitizer.goalDemandsFreshData("search online for cheap flights"))
        assertTrue(PlanResponseSanitizer.goalDemandsFreshData("search for the best pizza recipe"))
    }

    @Test
    fun `conversational and soft data goals do not demand a forced search`() {
        assertTrue(!PlanResponseSanitizer.goalDemandsFreshData("how are you today?"))
        assertTrue(!PlanResponseSanitizer.goalDemandsFreshData("what can you do?"))
        assertTrue(!PlanResponseSanitizer.goalDemandsFreshData("tell me about the weather app you like"))
        assertTrue(!PlanResponseSanitizer.goalDemandsFreshData("what is the current events quiz about"))
        // Word edges matter: "researching" must not contain " google ",
        // "bookmark" must not contain " look it up ".
        assertTrue(!PlanResponseSanitizer.goalDemandsFreshData("I am researching a topic for school"))
    }

    @Test
    fun `a chat-only plan defers an explicit google command`() {
        // "google" is not in DATA_WORDS — before round-7 a CHAT plan against
        // such a goal slipped past both deferral gates.
        assertTrue(
            PlanResponseSanitizer.planDefersGoal(
                listOf("CHAT"), "google the capital of France for me"
            )
        )
    }

    // --- stepResultSummary (round-7 ask-answer round-trip) ---

    @Test
    fun `an ask_user step contributes the user's answer to the summary`() {
        // Run-102 cap21: "Pune" (4 chars) was dropped by the length>5 filter
        // and the summary collapsed to "All done!".
        val ask = PlanStep(
            stepId = "s1", order = 1,
            description = "Ask user which city they prefer, Pune or Mumbai",
            action = "ASK_USER",
            status = StepStatus.COMPLETED,
            result = "Pune"
        )
        val summary = PlanResponseSanitizer.stepResultSummary(listOf(ask))
        assertTrue(summary != null && summary.contains("Pune"))
    }

    @Test
    fun `chat steps stay out of the summary and short non-ask results stay out`() {
        val chat = PlanStep(
            stepId = "s1", order = 1, description = "Reply", action = "CHAT",
            status = StepStatus.COMPLETED, result = "Here is a long conversational answer that was already delivered."
        )
        val shortResult = PlanStep(
            stepId = "s2", order = 2, description = "Do the thing", action = "SET_ALARM",
            status = StepStatus.COMPLETED, result = "7am" // 3 chars — dropped
        )
        assertNull(PlanResponseSanitizer.stepResultSummary(listOf(chat, shortResult)))
    }

    @Test
    fun `regular step results join into the summary`() {
        val search = PlanStep(
            stepId = "s1", order = 1, description = "Search the web", action = "WEB_SEARCH",
            status = StepStatus.COMPLETED,
            result = "Bitcoin is trading at $83,476 according to CoinDesk."
        )
        val write = PlanStep(
            stepId = "s2", order = 2, description = "Write the file", action = "WRITE_FILE",
            status = StepStatus.COMPLETED, result = "Saved Documents/report.html (4 KB)"
        )
        val summary = PlanResponseSanitizer.stepResultSummary(listOf(search, write))
        assertTrue(summary != null)
        assertTrue(summary!!.contains("CoinDesk"))
        assertTrue(summary.contains("report.html"))
    }

    @Test
    fun `failed steps never contribute`() {
        val failed = PlanStep(
            stepId = "s1", order = 1, description = "Search", action = "WEB_SEARCH",
            status = StepStatus.FAILED, result = "partial results should not leak into success"
        )
        assertNull(PlanResponseSanitizer.stepResultSummary(listOf(failed)))
    }

    // --- v1.3.0 round 21: the 2026-10-03 field failures ------------------
    // Three screenshots: "price of Nvidia stock" (twice) and "create a pdf of
    // a resume" both came back as CHAT plans claiming tools were
    // unavailable — the wrapper-form parse path had no deferral gate.

    @Test
    fun `a chat-only wrapper plan defers the nvidia stock goal`() {
        assertTrue(
            PlanResponseSanitizer.planDefersGoal(
                listOf("CHAT"), "price of Nvidia stock"
            )
        )
    }

    @Test
    fun `a chat-only wrapper plan defers the resume pdf goal`() {
        assertTrue(
            PlanResponseSanitizer.planDefersGoal(
                listOf("CHAT"),
                "ok create a pdf of a resume of mine with synthetic data with no image"
            )
        )
    }

    @Test
    fun `translate and calculate plans are not deferrals`() {
        // The planning prompt's own dependency rule names these as data
        // producers; the gate must agree (round-21 DATA_ACTIONS sync).
        assertTrue(!PlanResponseSanitizer.planDefersGoal(listOf("TRANSLATE"), "translate hello to french"))
        assertTrue(!PlanResponseSanitizer.planDefersGoal(listOf("CALCULATE"), "calculate 234 times 19"))
        assertTrue(!PlanResponseSanitizer.planDefersGoal(listOf("ANALYZE_SCREENSHOT"), "analyze this screenshot"))
    }

    @Test
    fun `chatty goals never trip the precise live-data gate`() {
        // The pre-existing false positive: bare "today" in DATA_WORDS made
        // "how are you today" look like a deferred data ask.
        assertTrue(!PlanResponseSanitizer.goalNeedsLiveData("how are you today"))
        assertTrue(!PlanResponseSanitizer.goalNeedsLiveData("what is the current state of the empire"))
        assertTrue(!PlanResponseSanitizer.goalNeedsLiveData("golden retriever facts"))
        assertTrue(!PlanResponseSanitizer.planDefersGoal(listOf("CHAT"), "how are you today"))
        assertTrue(PlanResponseSanitizer.goalNeedsLiveData("price of Nvidia stock"))
        assertTrue(PlanResponseSanitizer.goalNeedsLiveData("ok tell me the price of xauusd"))
    }

    @Test
    fun `the exact field refusal replies are detected`() {
        // 20:19 Nvidia
        assertTrue(
            PlanResponseSanitizer.replyRefusesGoal(
                "I can't pull a live quote right now (no tool access in this session), " +
                    "but here's what I know as a reference point: NVIDIA Corp (NASDAQ: NVDA)"
            )
        )
        // 20:22 Nvidia
        assertTrue(
            PlanResponseSanitizer.replyRefusesGoal(
                "I don't have a live quote feed available right now, so I can't give you " +
                    "a verified current number for NVIDIA (NVDA)"
            )
        )
        // 20:25 PDF
        assertTrue(
            PlanResponseSanitizer.replyRefusesGoal(
                "I can't create the PDF right now — file-generation tools aren't " +
                    "available in this session, so no file card will appear."
            )
        )
        // 20:11 gold
        assertTrue(
            PlanResponseSanitizer.replyRefusesGoal(
                "I wasn't able to pull an actual live XAU/USD number — the quote feed " +
                    "returned only page listings rather than a price"
            )
        )
        // Grounded answers are not refusals.
        assertTrue(!PlanResponseSanitizer.replyRefusesGoal("Gold is at $2,109 per ounce per Kitco."))
    }

    @Test
    fun `a late partial-failure mention is not a refusal`() {
        // The window is the first 260 chars — a grounded reply that mentions a
        // partial failure deep in its body stays legitimate.
        val grounded = ("x".repeat(300)) + " The live-rates feed couldn't find the ticker, but BSE shows ₹15.50."
        assertTrue(!PlanResponseSanitizer.replyRefusesGoal(grounded))
    }

    // --- v1.3.0 round 24: the report-verb routing fix (cap22/cap3 E2E) ---

    @Test
    fun `report as a verb is not a concrete artifact ask`() {
        // "search the price and REPORT the source URL" — the planner-rewritten
        // goal from the cap22 failure; "report" is the VERB, not a deliverable.
        assertTrue(
            !PlanResponseSanitizer.goalWantsConcreteArtifact(
                "Search the web for the current Bitcoin price in USD and report the source URL"
            )
        )
        assertTrue(
            !PlanResponseSanitizer.goalWantsConcreteArtifact(
                "Fetch https://example.com in-app and report the page's main heading"
            )
        )
        // The loose heuristic reads the same goals as artifact asks — that
        // is exactly the misroute round 24 fixes at the call site.
        assertTrue(
            PlanResponseSanitizer.goalWantsArtifact(
                "Search the web for the current Bitcoin price in USD and report the source URL"
            )
        )
    }

    @Test
    fun `concrete format words are concrete artifact asks`() {
        assertTrue(PlanResponseSanitizer.goalWantsConcreteArtifact("write a deep research report about solar energy growth in india as a pdf"))
        assertTrue(PlanResponseSanitizer.goalWantsConcreteArtifact("can u create a award winning website in html"))
        assertTrue(PlanResponseSanitizer.goalWantsConcreteArtifact("ok create a pdf of a resume of mine with synthetic data with no image"))
        assertTrue(!PlanResponseSanitizer.goalWantsConcreteArtifact("price of Nvidia stock"))
        assertTrue(!PlanResponseSanitizer.goalWantsConcreteArtifact("how are you today"))
    }

    @Test
    fun `a long refusal against a data goal is a deferral at any length`() {
        val slop = "I can't pull a live quote right now (no tool access in this session), " +
            "but here's what I know as a reference point: " + "context ".repeat(200)
        assertTrue(slop.length > 600)
        assertTrue(PlanResponseSanitizer.proseDeclinesAction(slop, "price of Nvidia stock"))
    }
}
