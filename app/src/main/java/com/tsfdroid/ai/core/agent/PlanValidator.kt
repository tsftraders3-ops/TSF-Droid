package com.tsfdroid.ai.core.agent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.tsfdroid.ai.actions.ActionDispatcher
import com.tsfdroid.ai.data.db.dao.UnknownActionDao
import com.tsfdroid.ai.data.db.entities.UnknownActionEntity
import com.tsfdroid.ai.data.models.Plan
import com.tsfdroid.ai.data.models.PlanStep
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlanValidator @Inject constructor(
    private val actionDispatcher: dagger.Lazy<ActionDispatcher>,
    private val unknownActionDao: dagger.Lazy<UnknownActionDao>
) {

    companion object {
        private val DATA_PRODUCING_ACTIONS = setOf(
            "GET_DIRECTIONS", "GET_WEATHER", "GET_NEWS", "CALCULATE",
            "CURRENCY_CONVERT", "TRANSLATE", "WEB_SEARCH", "SUMMARIZE_URL",
            "CHECK_STOCK", "DEFINE_WORD", "CONVERT_UNITS", "FACT_CHECK",
            "GET_SYSTEM_INFO", "CHECK_TRAFFIC", "CHECK_FLIGHT", "TRACK_DELIVERY",
            "CHECK_BALANCE", "LIST_CALENDAR_TODAY", "LIST_CALENDAR_WEEK",
            "READ_MESSAGES", "READ_EMAILS", "READ_NOTES", "READ_FILE",
            "LIST_FILES", "GET_SCREEN_TEXT", "LIST_INSTALLED_APPS",
            "ASK_USER", "SPLIT_BILL", "ANALYZE_SCREENSHOT",
            "READ_AND_REMEMBER_SCREEN", "RECALL_MEMORY", "QUERY_KNOWLEDGE_GRAPH"
        )
    }

    fun validatePlan(plan: Plan): List<String> {
        val errors = mutableListOf<String>()
        for (step in plan.steps) {
            val err = validateStep(step)
            if (err != null) errors.add(err)
        }
        return errors
    }

    fun validateStep(step: PlanStep): String? {
        if (!actionDispatcher.get().isRegistered(step.action)) {
            return "Action '${step.action}' is not registered."
        }
        return null
    }

    suspend fun validateAndFix(plan: Plan, context: Context): Plan {
        val finalSteps = mutableListOf<PlanStep>()
        var currentOrder = 1

        for (step in plan.steps) {
            var updatedStep = step
            val isReg = actionDispatcher.get().isRegistered(step.action)

            if (!isReg) {
                when (step.action.uppercase()) {
                    "VERIFY_APP", "SECURITY_CHECK" -> {
                        updatedStep = step.copy(action = "GET_SYSTEM_INFO")
                        logUnknownAction(step.action, plan.goal, "AUTO_FIXED")
                    }
                    "LAUNCH_APP", "OPEN_APP_OR_WEBSITE" -> {
                        val isWebsite = step.action == "OPEN_APP_OR_WEBSITE" && (
                            step.params.containsKey("url") ||
                            step.params.containsKey("website") ||
                            step.params.containsKey("link") ||
                            step.params.values.any { it.startsWith("http") }
                        )
                        if (isWebsite) {
                            val urlValue = step.params["url"]
                                ?: step.params["website"]
                                ?: step.params["link"]
                                ?: step.params.values.firstOrNull { it.startsWith("http") }
                                ?: ""
                            updatedStep = step.copy(action = "SUMMARIZE_URL", params = mapOf("url" to urlValue))
                        } else {
                            val appNameValue = step.params["appName"]
                                ?: step.params["app"]
                                ?: step.params["packageName"]
                                ?: step.params["package"]
                                ?: ""
                            updatedStep = step.copy(action = "OPEN_APP", params = mapOf("appName" to appNameValue))
                        }
                        logUnknownAction(step.action, plan.goal, "AUTO_FIXED")
                    }
                    else -> {
                        logUnknownAction(step.action, plan.goal, "FAILED")
                    }
                }
            }

            // v1.6.0 (field P0-8c): an action that resolves to NOTHING (not
            // registered, not aliasable, not semantic) fails at VALIDATION time -
            // the field's invented 'TAP' died at dispatch only after 19 minutes
            // of accessibility execution. The step becomes an honest chat note
            // the user actually reads.
            if (!actionDispatcher.get().isRegistered(updatedStep.action) &&
                actionDispatcher.get().previewResolvedAction(updatedStep.action) == null
            ) {
                android.util.Log.w(
                    "PlanValidator",
                    "step action '${updatedStep.action}' unresolvable - failing at validation, not dispatch"
                )
                updatedStep = updatedStep.copy(
                    action = "CHAT",
                    params = mapOf(
                        "response" to "One step I planned ('${step.action}') doesn't exist " +
                            "on this device, so I skipped it and continued with the rest."
                    ),
                    description = "Skipped unavailable action '${step.action}'"
                )
            }

            // v1.6.0 (field P0-8a, round 6 — the b3 E2E failure): the goal
            // SAYS "a markdown file called fieldfix_marker.md" but the model's
            // plan shipped the WRITE step with an EMPTY filePath — the step
            // parked on a needs-input prompt asking for a name the user had
            // already given. Fill it from the goal's own requested filenames
            // (first one no sibling step claims), falling back to the goal
            // slug + implied extension. The user only gets asked when the
            // goal genuinely leaves the name open.
            if (updatedStep.action.uppercase() == "WRITE_FILE" ||
                updatedStep.action.uppercase() == "CREATE_PDF"
            ) {
                val siblingPaths = plan.steps
                    .filter { it !== step }
                    .mapNotNull { it.params["filePath"]?.trim()?.takeIf { p -> p.isNotBlank() } }
                    .toSet()
                val fill = GoalContract.deliverableNameForWriteStep(plan.goal, updatedStep.params, siblingPaths)
                if (fill != null) {
                    android.util.Log.w(
                        "PlanValidator",
                        "blank ${updatedStep.action} filePath filled from the goal: '$fill'"
                    )
                    updatedStep = updatedStep.copy(
                        params = updatedStep.params.toMutableMap().apply { put("filePath", fill) }
                    )
                }
            }

            // v1.6.0 (field P0-3): CALCULATE's expression must be pure
            // arithmetic - the gold turn dispatched
            // "14780 - 14200 * 100 / 14200, using prices found in steps s1
            // and s2" and the calculation died on the prose.
            if (updatedStep.action.uppercase() == "CALCULATE") {
                val expr = updatedStep.params["expression"]
                if (!expr.isNullOrBlank()) {
                    val clean = GoalContract.sanitizeCalculateExpression(expr)
                    if (clean != expr && clean.isNotBlank()) {
                        android.util.Log.w(
                            "PlanValidator",
                            "CALCULATE expression sanitized: '${expr.take(50)}' -> '$clean'"
                        )
                        updatedStep = updatedStep.copy(
                            params = updatedStep.params.toMutableMap().apply { put("expression", clean) }
                        )
                    }
                }
            }

            // v1.3.1 round 4 (the third gold lesson): a SINGLE defeatist
            // ASK_USER step — "I'm not able to pull live market data in this
            // session" — parks the turn on a question the user cannot
            // usefully answer while real searches were available (run
            // 36987915019: zero searches, a 54-char apology). Rewritten into
            // a real search; legitimate asks never match the vocabulary.
            val askRepair = StepRepair.defeatistAskToSearch(
                updatedStep.action, updatedStep.params, plan.goal, plan.steps.size
            )
            if (askRepair != null) {
                android.util.Log.w(
                    "PlanValidator",
                    "defeatist single-step ASK_USER rewritten to WEB_SEARCH (goal='${plan.goal.take(60)}')"
                )
                updatedStep = updatedStep.copy(action = askRepair.first, params = askRepair.second)
            }

            // v1.3.1 (the xauusd field report): a FETCH_URL/SUMMARIZE_URL step
            // whose url slot carries a PHRASE ("web fetch the price of xauusd"
            // → url="the price of xauusd") is a search in disguise — the fetch
            // would attempt https://the price of xauusd and fail all three
            // strategies, then the WEB_SEARCH fallback would inherit the same
            // phrase-in-`url` params and dead-end on the missing `query`.
            // Rewrite it deterministically into a real search.
            val fetchRepair = StepRepair.fetchToSearch(updatedStep.action, updatedStep.params, plan.goal)
                // v1.4.0 (run-37111962938, the second xauusd lesson): the url
                // was REAL (google.com/finance/quote/XAU-USD) but the page
                // paints its price with scripts — the fetched static HTML
                // carries no digits and the turn honestly reported "the
                // numeric quote wasn't included". A live-data goal fetching
                // a JS-rendered quote page is a CHECK_STOCK in disguise.
                ?: StepRepair.fetchToQuote(updatedStep.action, updatedStep.params, plan.goal)
            if (fetchRepair != null) {
                android.util.Log.w(
                    "PlanValidator",
                    "${updatedStep.action} with non-url '${updatedStep.params["url"] ?: updatedStep.params["query"]?.take(40)}' " +
                        "rewritten to WEB_SEARCH (goal='${plan.goal.take(60)}')"
                )
                updatedStep = updatedStep.copy(
                    action = fetchRepair.first,
                    params = fetchRepair.second,
                    description = if (updatedStep.description.isBlank()) "Search for the requested information"
                    else updatedStep.description
                )
            }

            // v1.3.0 round 19 (the gold-query lesson): a WEB_SEARCH whose
            // query degenerated to a lone generic word ("current" for the
            // goal "Fetch the current gold price") is repaired here — the
            // honest turn would otherwise deliver whatever the backend
            // returns for the torn-out adjective (a fintech's marketing
            // page as its "price data"). The replacement is derived
            // deterministically from the goal, which is always about the
            // substance of the ask.
            if (updatedStep.action.uppercase() == "WEB_SEARCH") {
                // v1.6.0 (field P1-7): a query built from a raw first-person
                // request NEVER ships to a public engine verbatim - the
                // 403-char morning-briefing ask ("my calendar events... read
                // my last 5 unread emails...") went out as one WEB_SEARCH.
                val rawQuery = updatedStep.params["query"]?.trim().orEmpty()
                val publicClause = GoalContract.publicSearchClause(rawQuery)
                if (publicClause != null) {
                    android.util.Log.w(
                        "PlanValidator",
                        "private first-person WEB_SEARCH query replaced with public clause '${publicClause.take(60)}'"
                    )
                    updatedStep = updatedStep.copy(
                        params = updatedStep.params.toMutableMap().apply { put("query", publicClause) }
                    )
                }
                // v1.3.1: first the missing/blank query (incl. alias slots) —
                // a blank one can't even be judged degenerate yet.
                val repairedQuery = StepRepair.repairSearchQuery(updatedStep.params, plan.goal)
                if (repairedQuery != null) {
                    android.util.Log.w(
                        "PlanValidator",
                        "blank WEB_SEARCH query filled from '${repairedQuery["query"]?.take(60)}' (goal='${plan.goal.take(60)}')"
                    )
                    updatedStep = updatedStep.copy(params = repairedQuery)
                }
                val query = updatedStep.params["query"]?.trim().orEmpty()
                if (SearchQueryQuality.isDegenerate(query)) {
                    val derived = SearchQueryQuality.fromGoal(plan.goal)
                    if (derived.isNotBlank() && derived.length >= query.length) {
                        android.util.Log.w(
                            "PlanValidator",
                            "degenerate WEB_SEARCH query '$query' replaced with '$derived' (goal='${plan.goal.take(60)}')"
                        )
                        val fixedParams = updatedStep.params.toMutableMap().apply { put("query", derived) }
                        updatedStep = updatedStep.copy(params = fixedParams)
                    }
                }
            }

            val commActions = listOf("SEND_WHATSAPP", "SEND_TELEGRAM", "MAKE_CALL", "SEND_SMS", "MAKE_VIDEO_CALL")
            if (commActions.contains(updatedStep.action.uppercase()) && updatedStep.params.containsKey("contact")) {
                val contactName = updatedStep.params["contact"] ?: ""
                if (contactName.isNotEmpty() && !isPhoneNumber(contactName) && !contactName.startsWith("@")) {
                    val resolvedPhone = resolveContactToPhoneNumber(context, contactName)
                    if (resolvedPhone != null) {
                        val updatedParams = updatedStep.params.toMutableMap().apply { put("contact", resolvedPhone) }
                        updatedStep = updatedStep.copy(order = currentOrder++, params = updatedParams)
                        finalSteps.add(updatedStep)
                    } else if (updatedStep.action.uppercase() == "SEND_TELEGRAM") {
                        // For Telegram, a contact name could also be a Telegram username directly
                        finalSteps.add(updatedStep.copy(order = currentOrder++))
                    } else {
                        val askStepId = "${updatedStep.stepId}_ask"
                        val askStep = PlanStep(
                            stepId = askStepId, order = currentOrder++,
                            description = "Ask user for contact number of '$contactName'",
                            action = "ASK_USER",
                            params = mapOf("question" to "I couldn't find a contact named '$contactName'. What is their phone number?"),
                            fallback = ""
                        )
                        finalSteps.add(askStep)
                        val updatedParams = updatedStep.params.toMutableMap().apply { put("contact", "$$askStepId") }
                        val updatedDependsOn = updatedStep.dependsOn.toMutableList().apply { if (!contains(askStepId)) add(askStepId) }
                        updatedStep = updatedStep.copy(order = currentOrder++, params = updatedParams, dependsOn = updatedDependsOn)
                        finalSteps.add(updatedStep)
                    }
                } else {
                    updatedStep = updatedStep.copy(order = currentOrder++)
                    finalSteps.add(updatedStep)
                }
            } else {
                updatedStep = updatedStep.copy(order = currentOrder++)
                finalSteps.add(updatedStep)
            }
        }

        val cleanedSteps = removeBadDependencies(finalSteps)

        // v1.4.0 (run-37118014660, the poisoned-backend window): a metals/
        // crypto price goal whose plan never calls CHECK_STOCK gets one
        // APPENDED — Yahoo's JSON endpoints are a different backend that
        // kept answering while DDG/Bing served garbage (round-1 evidence:
        // GC=F digits through the same window), so the answer engine always
        // has a digit-bearing source for the synthesis.
        // v1.6.0 (field P1-9): goal-coverage for reminders - "set a
        // reminder for 5pm tomorrow" silently vanished from the gold plan (no
        // step, no mention). A reminder phrase with no SET_REMINDER step gains
        // one, appended last so its data dependencies already ran.
        val reminderWanted = Regex(
            "(?i)\\b(remind me|set a reminder|set me a reminder|reminder to|reminder for)\\b"
        ).containsMatchIn(plan.goal)
        val reminderStep = if (reminderWanted &&
            cleanedSteps.none { it.action.uppercase() == "SET_REMINDER" }
        ) {
            val note = Regex("(?i)remind(?:er)? (?:me )?(?:to |for )(.{4,140})")
                .find(plan.goal)?.groupValues?.get(1)?.trim()
                ?: plan.goal.take(100)
            android.util.Log.w(
                "PlanValidator",
                "goal asks for a reminder but no SET_REMINDER step exists - appending one"
            )
            PlanStep(
                stepId = "reminder-coverage-${System.currentTimeMillis()}",
                order = cleanedSteps.size + 1,
                description = "Set the requested reminder ($note)",
                action = "SET_REMINDER",
                params = mapOf(
                    "title" to note.take(80),
                    "description" to "Created by TSF Droid from your request: ${plan.goal.take(120)}"
                ),
                fallback = ""
            )
        } else {
            null
        }

        val quoteAssist = StepRepair.priceGoalQuoteStep(plan.goal, cleanedSteps.map { it.action })
        val finalPlan = if (quoteAssist != null) {
            android.util.Log.w(
                "PlanValidator",
                "price goal '${plan.goal.take(60)}' gains a CHECK_STOCK(${quoteAssist.second["symbol"]}) step — an independent quote backend for the synthesis"
            )
            val quoteStep = PlanStep(
                stepId = "quote-assist-${System.currentTimeMillis()}",
                order = cleanedSteps.size + 1,
                description = "Fetch the live ${quoteAssist.second["symbol"]} quote (Yahoo JSON — independent of the search backends)",
                action = quoteAssist.first,
                params = quoteAssist.second,
                fallback = ""
            )
            plan.copy(steps = cleanedSteps + quoteStep + listOfNotNull(reminderStep), estimatedSteps = cleanedSteps.size + 1)
        } else if (reminderStep != null) {
            plan.copy(steps = cleanedSteps + reminderStep, estimatedSteps = cleanedSteps.size + 1)
        } else {
            plan.copy(steps = cleanedSteps, estimatedSteps = cleanedSteps.size)
        }
        return finalPlan
    }

    private fun removeBadDependencies(steps: List<PlanStep>): List<PlanStep> {
        return steps.map { step ->
            if (step.dependsOn.isEmpty()) return@map step
            val trueDeps = step.dependsOn.filter { depId ->
                val depStep = steps.find { it.stepId == depId }
                depStep != null && DATA_PRODUCING_ACTIONS.contains(depStep.action.uppercase())
            }
            step.copy(dependsOn = trueDeps)
        }
    }

    private suspend fun logUnknownAction(attemptedAction: String, goal: String, fixStatus: String) {
        try {
            unknownActionDao.get().insertUnknownAction(
                UnknownActionEntity(attemptedAction = attemptedAction, goal = goal, fixStatus = fixStatus)
            )
        } catch (e: Exception) { }
    }

    private fun isPhoneNumber(contact: String): Boolean {
        val cleaned = contact.replace(" ", "").replace("-", "")
        return cleaned.startsWith("+") || (cleaned.isNotEmpty() && cleaned.all { it.isDigit() })
    }

    // lint false positive: both cursors are closed by `?.use { }` on every path,
    // but the Recycle detector does not model Kotlin's use() inlining. See #67.
    @Suppress("Recycle")
    private fun resolveContactToPhoneNumber(context: Context, contact: String): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        try {
            val contentResolver = context.contentResolver
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val selectionExact = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?"
            contentResolver.query(uri, projection, selectionExact, arrayOf(contact.trim()), null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (idx >= 0) {
                        val number = cursor.getString(idx)
                        if (!number.isNullOrBlank()) return number.replace(" ", "").replace("-", "")
                    }
                }
            }
            val selectionLike = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            contentResolver.query(uri, projection, selectionLike, arrayOf("%${contact.trim()}%"), null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (idx >= 0) {
                        val number = cursor.getString(idx)
                        if (!number.isNullOrBlank()) return number.replace(" ", "").replace("-", "")
                    }
                }
            }
        } catch (e: Exception) { }
        return null
    }
}
