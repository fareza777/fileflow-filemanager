package com.fileflow.app.domain.rules

import com.fileflow.app.core.model.FileCategory
import com.fileflow.app.core.storage.ConflictPolicy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A rule is data, not code: it is serialised into one column so the rule
 * vocabulary can grow without a database migration every time a new condition
 * or action shows up.
 */
@Serializable
data class RuleSpec(
    val conditions: List<MatchCondition> = emptyList(),
    val matchMode: MatchMode = MatchMode.ALL,
    val action: ActionSpec = ActionSpec(),
    /** Empty means "every mounted volume". */
    val roots: List<String> = emptyList(),
    val recursive: Boolean = true,
    val skipHidden: Boolean = true,
    /** Safety valve for runaway rules; 0 disables the cap. */
    val maxFilesPerRun: Int = 5000,
    val schedule: ScheduleSpec = ScheduleSpec(),
    val notify: Boolean = true,
) {
    val isRunnable: Boolean
        get() = conditions.isNotEmpty() && action.isValid
}

@Serializable
enum class MatchMode { ALL, ANY }

@Serializable
sealed interface MatchCondition {

    val negate: Boolean

    @Serializable
    @SerialName("name")
    data class Name(
        val text: String,
        val mode: NameMode = NameMode.CONTAINS,
        override val negate: Boolean = false,
    ) : MatchCondition

    @Serializable
    @SerialName("extension")
    data class Extension(
        val extensions: List<String>,
        override val negate: Boolean = false,
    ) : MatchCondition

    @Serializable
    @SerialName("category")
    data class Category(
        val categories: List<String>,
        override val negate: Boolean = false,
    ) : MatchCondition

    @Serializable
    @SerialName("folder")
    data class SourceFolder(
        val folders: List<String>,
        override val negate: Boolean = false,
    ) : MatchCondition

    @Serializable
    @SerialName("size")
    data class Size(
        val minBytes: Long? = null,
        val maxBytes: Long? = null,
        override val negate: Boolean = false,
    ) : MatchCondition

    @Serializable
    @SerialName("age")
    data class Age(
        val olderThanDays: Int? = null,
        val newerThanDays: Int? = null,
        override val negate: Boolean = false,
    ) : MatchCondition
}

@Serializable
enum class NameMode { CONTAINS, STARTS_WITH, ENDS_WITH, WILDCARD, REGEX }

@Serializable
enum class ActionKind { MOVE, COPY, TRASH, DELETE, RENAME }

/**
 * Moving into a destination and then sorting into a dated or categorised
 * subfolder is the single most requested shape of "auto organise", so it is
 * part of the action rather than a second rule.
 */
@Serializable
enum class SubfolderStrategy { NONE, BY_CATEGORY, BY_MONTH, BY_EXTENSION }

@Serializable
data class ActionSpec(
    val kind: ActionKind = ActionKind.MOVE,
    val destination: String? = null,
    val subfolder: SubfolderStrategy = SubfolderStrategy.NONE,
    val renamePattern: String? = null,
    val conflictPolicy: ConflictPolicy = ConflictPolicy.RENAME,
) {
    val isValid: Boolean
        get() = when (kind) {
            ActionKind.MOVE, ActionKind.COPY -> !destination.isNullOrBlank()
            ActionKind.RENAME -> !renamePattern.isNullOrBlank()
            ActionKind.TRASH, ActionKind.DELETE -> true
        }
}

@Serializable
enum class ScheduleKind { OFF, HOURLY, DAILY, WEEKLY }

@Serializable
data class ScheduleSpec(
    val kind: ScheduleKind = ScheduleKind.OFF,
    val hour: Int = 21,
    val minute: Int = 0,
    /** ISO day numbers, 1 = Monday … 7 = Sunday. */
    val daysOfWeek: List<Int> = listOf(1, 2, 3, 4, 5, 6, 7),
    val intervalHours: Int = 6,
    val wifiOnly: Boolean = false,
    val chargingOnly: Boolean = false,
) {
    val isScheduled: Boolean get() = kind != ScheduleKind.OFF
}

/** A rule plus its identity, which is what the UI and the engine both work with. */
data class Rule(
    val id: Long,
    val name: String,
    val enabled: Boolean,
    val spec: RuleSpec,
    val createdAt: Long,
    val updatedAt: Long,
    val lastRunAt: Long?,
    val lastRunStatus: String?,
    val lastMatchedCount: Int,
    /**
     * The stored spec could not be read back — a rule written by a version with
     * a vocabulary this build does not know, or a corrupted row. It decodes to
     * an empty spec, which makes it silently unrunnable, so the fact is carried
     * up to the UI instead of being swallowed.
     */
    val specUnreadable: Boolean = false,
)

/** A rule that has not been persisted yet. */
data class RuleDraft(
    val id: Long = 0,
    val name: String = "",
    val enabled: Boolean = true,
    val spec: RuleSpec = RuleSpec(),
)

/** Where a single matched file is headed, resolved before anything moves. */
data class PlannedOperation(
    val sourcePath: String,
    val sourceName: String,
    val destinationPath: String?,
    val destinationName: String?,
    val sizeBytes: Long,
    val isDirectory: Boolean,
    val conflict: Boolean,
)

/** One rule's match, tagged with which rule produced it in a shared walk. */
data class RuleMatch(
    val ruleId: Long,
    val operation: PlannedOperation,
)

data class RulePreview(
    val operations: List<PlannedOperation>,
    val scanned: Int,
    val totalBytes: Long,
    val conflicts: Int,
    val foldersAffected: Int,
    val truncated: Boolean,
)

enum class RunTrigger { MANUAL, SCHEDULED, PREVIEW }

enum class RunStatus { RUNNING, SUCCESS, PARTIAL, FAILED, CANCELLED, UNDONE }

data class RunOutcome(
    val logId: Long,
    val status: RunStatus,
    val matched: Int,
    val succeeded: Int,
    val failed: Int,
    val bytesMoved: Long,
    val issues: List<String>,
    val cancelled: Boolean,
)

/**
 * Whether a rule that was scheduled to fire should actually run now.
 *
 * Workers for several rules can land in the same minute; the first one runs
 * them all and the rest find their work already done.
 */
object DueWindow {

    fun isDue(rule: Rule, now: Long): Boolean {
        if (!rule.enabled || !rule.spec.schedule.isScheduled) return false
        val last = rule.lastRunAt ?: return true
        return now - last >= minimumGap(rule.spec.schedule)
    }

    private fun minimumGap(schedule: ScheduleSpec): Long = when (schedule.kind) {
        ScheduleKind.OFF -> Long.MAX_VALUE
        ScheduleKind.HOURLY -> schedule.intervalHours.coerceAtLeast(1) * 3_600_000L * 55 / 60
        ScheduleKind.DAILY -> 20 * 3_600_000L
        ScheduleKind.WEEKLY -> 6 * 86_400_000L
    }
}

/** Human-readable helper used by the rule list and the preview header. */
object ConditionSummary {

    fun folderNames(condition: MatchCondition.SourceFolder): String =
        condition.folders.joinToString(", ") { it.substringAfterLast('/') }

    fun categoryLabels(condition: MatchCondition.Category): String =
        condition.categories.map { FileCategory.fromKey(it) }.joinToString(", ") {
            it.name.lowercase().replaceFirstChar(Char::uppercase)
        }
}
