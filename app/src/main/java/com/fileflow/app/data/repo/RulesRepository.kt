package com.fileflow.app.data.repo

import com.fileflow.app.core.storage.CollisionResolver
import com.fileflow.app.core.storage.ConflictPolicy
import com.fileflow.app.core.storage.VolumeRepository
import com.fileflow.app.data.db.RuleDao
import com.fileflow.app.data.db.RuleEntity
import com.fileflow.app.data.db.RuleLogDao
import com.fileflow.app.data.db.RuleLogEntity
import com.fileflow.app.domain.rules.ActionKind
import com.fileflow.app.domain.rules.ExecutionProgress
import com.fileflow.app.domain.rules.PlannedOperation
import com.fileflow.app.domain.rules.Rule
import com.fileflow.app.domain.rules.RuleDraft
import com.fileflow.app.domain.rules.RuleEngine
import com.fileflow.app.domain.rules.RuleExecutor
import com.fileflow.app.domain.rules.RuleJson
import com.fileflow.app.domain.rules.RulePreview
import com.fileflow.app.domain.rules.RuleScheduler
import com.fileflow.app.domain.rules.RunOutcome
import com.fileflow.app.domain.rules.RunTrigger
import com.fileflow.app.domain.rules.UndoManager
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.withContext

@Singleton
class RulesRepository @Inject constructor(
    private val ruleDao: RuleDao,
    private val logDao: RuleLogDao,
    private val engine: RuleEngine,
    private val executor: RuleExecutor,
    private val scheduler: RuleScheduler,
    private val undoManager: UndoManager,
    private val volumes: VolumeRepository,
) {

    fun observeRules(): Flow<List<Rule>> =
        ruleDao.observeAll().map { list -> list.map(RuleJson::toRule) }

    fun observeRule(id: Long): Flow<Rule?> =
        ruleDao.observe(id).map { entity -> entity?.let(RuleJson::toRule) }

    fun observeLogs(limit: Int = 200): Flow<List<RuleLogEntity>> =
        logDao.observeRecent(limit)

    fun observeLogsForRule(ruleId: Long): Flow<List<RuleLogEntity>> =
        logDao.observeForRule(ruleId)

    fun observeLastRunAt(): Flow<Long?> = logDao.observeLastRunAt()

    suspend fun get(id: Long): Rule? = withContext(Dispatchers.IO) {
        ruleDao.byId(id)?.let(RuleJson::toRule)
    }

    suspend fun save(draft: RuleDraft): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val json = RuleJson.encode(draft.spec)
        val id = if (draft.id == 0L) {
            ruleDao.insert(
                RuleEntity(
                    name = draft.name.trim().ifEmpty { "Untitled rule" },
                    enabled = draft.enabled,
                    specJson = json,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        } else {
            val existing = ruleDao.byId(draft.id)
            ruleDao.update(
                (existing ?: RuleEntity(
                    id = draft.id,
                    name = draft.name,
                    enabled = draft.enabled,
                    specJson = json,
                    createdAt = now,
                    updatedAt = now,
                )).copy(
                    name = draft.name.trim().ifEmpty { "Untitled rule" },
                    enabled = draft.enabled,
                    specJson = json,
                    updatedAt = now,
                )
            )
            draft.id
        }
        scheduler.sync(id)
        id
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        scheduler.cancel(id)
        ruleDao.delete(id)
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) = withContext(Dispatchers.IO) {
        ruleDao.setEnabled(id, enabled, System.currentTimeMillis())
        scheduler.sync(id)
    }

    /**
     * Read-only. Running this can never change a file — it only reports what
     * would happen, which is what the preview screen promises.
     */
    suspend fun preview(
        draft: RuleDraft,
        onProgress: (scanned: Int, matched: Int) -> Unit = { _, _ -> },
    ): Pair<RulePreview, List<PlannedOperation>> = withContext(Dispatchers.IO) {
        val roots = effectiveRoots(draft)
        val cap = draft.spec.maxFilesPerRun
        val operations = mutableListOf<PlannedOperation>()
        var scanned = 0
        var truncated = false

        val flow = engine.plan(
            spec = draft.spec,
            roots = roots,
            onScanned = { seen ->
                scanned = seen
                onProgress(seen, operations.size)
            },
        )
        // One extra match tells us the plan was cut short by the cap.
        val limited = if (cap > 0) flow.take(cap + 1) else flow
        limited.collect { operation ->
            if (cap > 0 && operations.size >= cap) {
                truncated = true
                return@collect
            }
            operations += operation
        }
        onProgress(scanned, operations.size)

        // Two matched files can want the same destination — two camera names
        // that render to the same timestamp, for instance. Resolving that here,
        // with the same resolver the rename engine uses, is what keeps this
        // preview identical to what the executor will actually do.
        val claimed = mutableSetOf<String>()
        val resolved = operations.map { operation ->
            val destination = operation.destinationPath ?: return@map operation
            val directory = File(destination).parentFile ?: return@map operation
            val wanted = destination.substringAfterLast('/')
            val unique = CollisionResolver.unique(directory, wanted, claimed)
            if (unique == wanted) {
                operation
            } else {
                operation.copy(
                    destinationPath = "${directory.absolutePath}/$unique",
                    destinationName = unique,
                    conflict = true,
                )
            }
        }

        val preview = RulePreview(
            operations = resolved.sortedBy { it.destinationPath ?: it.sourcePath },
            scanned = scanned,
            totalBytes = resolved.sumOf { it.sizeBytes },
            conflicts = resolved.count { it.conflict },
            foldersAffected = resolved.mapNotNull { it.destinationPath?.substringBeforeLast('/') }
                .distinct()
                .size,
            truncated = truncated,
        )
        preview to resolved
    }

    /**
     * Builds the plan itself, for "run now" without an explicit preview.
     */
    suspend fun runNow(
        rule: Rule,
        onProgress: (ExecutionProgress) -> Unit = {},
    ): RunOutcome = withContext(Dispatchers.IO) {
        val draft = RuleDraft(id = rule.id, name = rule.name, enabled = rule.enabled, spec = rule.spec)
        val (_, operations) = preview(draft)
        executor.execute(rule, operations, RunTrigger.MANUAL, onProgress)
    }

    /** Runs a plan the user has already approved on the preview screen. */
    suspend fun runPlan(
        rule: Rule,
        operations: List<PlannedOperation>,
        trigger: RunTrigger = RunTrigger.MANUAL,
        onProgress: (ExecutionProgress) -> Unit = {},
    ): RunOutcome = withContext(Dispatchers.IO) {
        executor.execute(rule, operations, trigger, onProgress)
    }

    suspend fun undoRun(
        logId: Long,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): UndoSummary = withContext(Dispatchers.IO) {
        val result = undoManager.undoGroup("run-$logId", onProgress)
        UndoSummary(result.restored, result.failed, result.skipped, result.errors)
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        logDao.clear()
    }

    /** Human summary of a stored rule, used by the rule cards. */
    fun describe(rule: Rule): String = engine.describe(rule.spec)

    private fun effectiveRoots(draft: RuleDraft): List<String> =
        if (draft.spec.roots.isEmpty()) volumes.roots() else draft.spec.roots

    companion object {
        val DEFAULT_CONFLICT = ConflictPolicy.RENAME
        val PERMANENT_ACTIONS = setOf(ActionKind.DELETE)
    }
}

data class UndoSummary(
    val restored: Int,
    val failed: Int,
    val skipped: Int,
    val errors: List<String> = emptyList(),
)
