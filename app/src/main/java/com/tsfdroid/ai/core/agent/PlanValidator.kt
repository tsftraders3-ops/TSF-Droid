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

            // v1.3.1 (the xauusd field report): a FETCH_URL/SUMMARIZE_URL step
            // whose url slot carries a PHRASE ("web fetch the price of xauusd"
            // → url="the price of xauusd") is a search in disguise — the fetch
            // would attempt https://the price of xauusd and fail all three
            // strategies, then the WEB_SEARCH fallback would inherit the same
            // phrase-in-`url` params and dead-end on the missing `query`.
            // Rewrite it deterministically into a real search.
            val fetchRepair = StepRepair.fetchToSearch(updatedStep.action, updatedStep.params, plan.goal)
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
        return plan.copy(steps = cleanedSteps, estimatedSteps = cleanedSteps.size)
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
