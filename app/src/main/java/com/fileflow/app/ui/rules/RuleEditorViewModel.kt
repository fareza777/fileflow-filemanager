package com.fileflow.app.ui.rules

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fileflow.app.core.model.FileCategory
import com.fileflow.app.core.storage.ConflictPolicy
import com.fileflow.app.data.repo.BrowseRepository
import com.fileflow.app.data.repo.RulesRepository
import com.fileflow.app.domain.rules.ActionKind
import com.fileflow.app.domain.rules.ActionSpec
import com.fileflow.app.domain.rules.MatchCondition
import com.fileflow.app.domain.rules.MatchMode
import com.fileflow.app.domain.rules.NameMode
import com.fileflow.app.domain.rules.RuleDraft
import com.fileflow.app.domain.rules.RuleMatcher
import com.fileflow.app.domain.rules.RuleSpec
import com.fileflow.app.domain.rules.ScheduleKind
import com.fileflow.app.domain.rules.ScheduleSpec
import com.fileflow.app.domain.rules.SubfolderStrategy
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RuleEditorState(
    val draft: RuleDraft = RuleDraft(),
    val isNew: Boolean = true,
    val loaded: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
    val savedId: Long = 0,
)

@HiltViewModel
class RuleEditorViewModel @Inject constructor(
    private val repository: RulesRepository,
    private val browseRepository: BrowseRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(RuleEditorState())
    val state: StateFlow<RuleEditorState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private val ruleId: Long = savedStateHandle.get<Long>(ARG_RULE_ID) ?: 0L
    private val seedQuery: String = savedStateHandle.get<String>(ARG_QUERY) ?: ""

    init {
        if (ruleId == 0L) {
            // Arriving from a search means the user already said what they are
            // looking for, so the rule starts with that condition filled in.
            val seeded = if (seedQuery.isNotBlank()) {
                RuleSpec(conditions = listOf(MatchCondition.Name(seedQuery)))
            } else {
                RuleSpec()
            }
            _state.update {
                it.copy(
                    loaded = true,
                    isNew = true,
                    draft = RuleDraft(name = seedQuery, spec = seeded),
                )
            }
        } else {
            viewModelScope.launch {
                val rule = repository.get(ruleId)
                if (rule == null) {
                    _state.update { it.copy(loaded = true, error = "Rule not found") }
                } else {
                    _state.update {
                        it.copy(
                            loaded = true,
                            isNew = false,
                            draft = RuleDraft(
                                id = rule.id,
                                name = rule.name,
                                enabled = rule.enabled,
                                spec = rule.spec,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun setName(name: String) {
        _state.update { current ->
            current.copy(draft = current.draft.copy(name = name), error = null)
        }
    }

    fun setMatchMode(mode: MatchMode) = update { spec -> spec.copy(matchMode = mode) }

    fun setRecursive(enabled: Boolean) = update { it.copy(recursive = enabled) }

    fun setSkipHidden(enabled: Boolean) = update { it.copy(skipHidden = enabled) }

    fun setWholeStorage(whole: Boolean) = update { it.copy(roots = if (whole) emptyList() else it.roots) }

    fun addRoot(path: String) = update { spec ->
        if (spec.roots.contains(path)) spec else spec.copy(roots = spec.roots + path)
    }

    fun removeRoot(path: String) = update { spec ->
        spec.copy(roots = spec.roots - path)
    }

    fun addCondition(condition: MatchCondition) = update { spec ->
        spec.copy(conditions = spec.conditions + condition)
    }

    fun updateCondition(index: Int, condition: MatchCondition) = update { spec ->
        if (index !in spec.conditions.indices) spec
        else spec.copy(
            conditions = spec.conditions.toMutableList().also { it[index] = condition }
        )
    }

    fun removeCondition(index: Int) = update { spec ->
        spec.copy(conditions = spec.conditions.filterIndexed { i, _ -> i != index })
    }

    fun setActionKind(kind: ActionKind) = update { spec ->
        spec.copy(
            action = spec.action.copy(
                kind = kind,
                destination = if (kind == ActionKind.MOVE || kind == ActionKind.COPY) {
                    spec.action.destination ?: spec.roots.firstOrNull()
                } else {
                    spec.action.destination
                },
            )
        )
    }

    fun setDestination(path: String) = update { spec ->
        spec.copy(action = spec.action.copy(destination = path))
    }

    fun setSubfolder(strategy: SubfolderStrategy) = update { spec ->
        spec.copy(action = spec.action.copy(subfolder = strategy))
    }

    fun setConflictPolicy(policy: ConflictPolicy) = update { spec ->
        spec.copy(action = spec.action.copy(conflictPolicy = policy))
    }

    fun setRenamePattern(pattern: String) = update { spec ->
        spec.copy(action = spec.action.copy(renamePattern = pattern))
    }

    fun setScheduleKind(kind: ScheduleKind) = update { spec ->
        spec.copy(schedule = spec.schedule.copy(kind = kind))
    }

    fun setScheduleTime(hour: Int, minute: Int) = update { spec ->
        spec.copy(schedule = spec.schedule.copy(hour = hour, minute = minute))
    }

    fun toggleScheduleDay(day: Int) = update { spec ->
        val days = if (day in spec.schedule.daysOfWeek) {
            spec.schedule.daysOfWeek - day
        } else {
            (spec.schedule.daysOfWeek + day).sorted()
        }
        spec.copy(schedule = spec.schedule.copy(daysOfWeek = days.ifEmpty { listOf(day) }))
    }

    fun setWifiOnly(enabled: Boolean) = update { spec ->
        spec.copy(schedule = spec.schedule.copy(wifiOnly = enabled))
    }

    fun setChargingOnly(enabled: Boolean) = update { spec ->
        spec.copy(schedule = spec.schedule.copy(chargingOnly = enabled))
    }

    fun setNotify(enabled: Boolean) = update { spec -> spec.copy(notify = enabled) }

    fun saveAndPreview() {
        val current = _state.value
        val spec = current.draft.spec
        val problem = validate(current.draft.name, spec)
        if (problem != null) {
            _state.update { it.copy(error = problem) }
            return
        }
        viewModelScope.launch {
            val id = repository.save(current.draft)
            _state.update { it.copy(saved = true, savedId = id, error = null) }
        }
    }

    fun save() {
        val current = _state.value
        val problem = validate(current.draft.name, current.draft.spec)
        if (problem != null) {
            _state.update { it.copy(error = problem) }
            return
        }
        viewModelScope.launch {
            val id = repository.save(current.draft)
            _state.update { it.copy(saved = true, savedId = id, error = null) }
            _messages.send("Rule saved")
        }
    }

    private fun validate(name: String, spec: com.fileflow.app.domain.rules.RuleSpec): String? = when {
        name.isBlank() -> "name"
        spec.conditions.isEmpty() -> "condition"
        spec.conditions.any { it.isInvalidCondition() } -> "value"
        !spec.action.isValid -> "destination"
        else -> null
    }

    fun isWholeStorage(): Boolean = _state.value.draft.spec.roots.isEmpty()

    fun defaultRoot(): String = browseRepository.primaryRoot()

    private fun MatchCondition.isInvalidCondition(): Boolean = when (this) {
        is MatchCondition.Name -> text.isBlank() ||
            (mode == NameMode.REGEX && !RuleMatcher.isValidRegex(text))
        is MatchCondition.Extension -> extensions.isEmpty()
        is MatchCondition.Category -> categories.isEmpty()
        is MatchCondition.SourceFolder -> folders.isEmpty()
        is MatchCondition.Size -> minBytes == null && maxBytes == null
        is MatchCondition.Age -> olderThanDays == null && newerThanDays == null
    }

    private fun update(
        block: (com.fileflow.app.domain.rules.RuleSpec) -> com.fileflow.app.domain.rules.RuleSpec,
    ) {
        _state.update { current ->
            current.copy(draft = current.draft.copy(spec = block(current.draft.spec)), error = null)
        }
    }

    companion object {
        const val ARG_RULE_ID = "ruleId"
        const val ARG_QUERY = "q"
    }
}

/** Convenience for the category condition's multi-select. */
fun FileCategory.toConditionKey(): String = key
