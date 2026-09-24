package com.tsfdroid.ai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tsfdroid.ai.data.db.dao.MacroDao
import com.tsfdroid.ai.data.db.entities.MacroEntity
import com.tsfdroid.ai.data.models.Macro
import com.tsfdroid.ai.data.models.PlanStep
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

@HiltViewModel
class MacroViewModel @Inject constructor(
    private val macroDao: MacroDao
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }

    val macros: StateFlow<List<Macro>> = macroDao.getAllMacrosFlow()
        .map { entities ->
            entities.map { entity ->
                val steps = try {
                    json.decodeFromString<List<PlanStep>>(entity.stepsJson)
                } catch (e: Exception) {
                    emptyList()
                }
                Macro(
                    id = entity.id,
                    name = entity.name,
                    trigger = entity.trigger,
                    steps = steps,
                    isSystem = entity.isSystem,
                    isEnabled = entity.isEnabled
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun saveMacro(macro: Macro) {
        viewModelScope.launch {
            val stepsJson = json.encodeToString(macro.steps)
            macroDao.insertMacro(
                MacroEntity(
                    id = macro.id,
                    name = macro.name,
                    trigger = macro.trigger,
                    stepsJson = stepsJson,
                    isSystem = macro.isSystem,
                    isEnabled = macro.isEnabled
                )
            )
        }
    }

    fun deleteMacro(id: String) {
        viewModelScope.launch {
            macroDao.deleteMacro(id)
        }
    }

    fun toggleMacro(id: String, isEnabled: Boolean) {
        viewModelScope.launch {
            val macroEntity = macroDao.getMacroById(id)
            if (macroEntity != null) {
                macroDao.insertMacro(macroEntity.copy(isEnabled = isEnabled))
            }
        }
    }
}
