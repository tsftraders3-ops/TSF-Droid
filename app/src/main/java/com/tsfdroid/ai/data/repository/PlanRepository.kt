package com.tsfdroid.ai.data.repository

import com.tsfdroid.ai.data.db.dao.PlanDao
import com.tsfdroid.ai.data.db.entities.PlanEntity
import com.tsfdroid.ai.data.models.Plan
import com.tsfdroid.ai.data.models.PlanStatus
import com.tsfdroid.ai.data.models.PlanStep
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlanRepository @Inject constructor(
    private val planDao: PlanDao
) {
    private val json = Json { ignoreUnknownKeys = true }

    val allPlans: Flow<List<Plan>> = planDao.getAllPlansFlow().map { entities ->
        entities.map { entity -> mapEntityToPlan(entity) }
    }

    suspend fun getPlanById(planId: String): Plan? {
        val entity = planDao.getPlanById(planId) ?: return null
        return mapEntityToPlan(entity)
    }

    suspend fun savePlan(plan: Plan) {
        val stepsJson = json.encodeToString(plan.steps)
        planDao.insertOrUpdatePlan(
            PlanEntity(
                planId = plan.planId,
                goal = plan.goal,
                estimatedDuration = plan.estimatedDuration,
                estimatedSteps = plan.estimatedSteps,
                stepsJson = stepsJson,
                status = plan.status.name,
                createdAt = plan.createdAt
            )
        )
    }

    suspend fun deletePlan(planId: String) {
        planDao.deletePlan(planId)
    }

    private fun mapEntityToPlan(entity: PlanEntity): Plan {
        val steps = try {
            json.decodeFromString<List<PlanStep>>(entity.stepsJson)
        } catch (e: Exception) {
            emptyList()
        }
        return Plan(
            planId = entity.planId,
            goal = entity.goal,
            estimatedDuration = entity.estimatedDuration,
            estimatedSteps = entity.estimatedSteps,
            steps = steps,
            status = PlanStatus.valueOf(entity.status),
            createdAt = entity.createdAt
        )
    }
}
