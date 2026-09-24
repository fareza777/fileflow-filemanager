package com.fileflow.app.domain.sync

import kotlinx.serialization.Serializable

/** How often a saved sync runs on its own. */
@Serializable
enum class SyncInterval(val hours: Long) {
    SIX_HOURS(6),
    DAILY(24),
    WEEKLY(168),
    ;

    /** WorkManager refuses anything under fifteen minutes; all of these pass. */
    val label: String
        get() = when (this) {
            SIX_HOURS -> "every 6 hours"
            DAILY -> "daily"
            WEEKLY -> "weekly"
        }
}

/**
 * A folder pair the user has decided to keep in step.
 *
 * Saving the pair and the direction is not a convenience — it is what makes a
 * two-way sync honest. The screen can only compare what it can see right now,
 * and a job that runs at 3 a.m. has no user to ask; recording the intent when
 * they were looking at it is the difference between a schedule and a guess.
 */
@Serializable
data class SyncJob(
    val id: String,
    val left: String,
    val right: String,
    val direction: SyncDirection,
    val mode: SyncMode,
    val interval: SyncInterval,
    val compareContent: Boolean = false,
    val enabled: Boolean = true,
    /** Wall clock of the last finished run, and what it did. */
    val lastRunAt: Long = 0L,
    val lastCopied: Int = 0,
    val lastDeleted: Int = 0,
    val lastConflicts: Int = 0,
    val lastFailed: Int = 0,
    /** True when the last run could not finish at all. */
    val lastError: String? = null,
) {
    val pair: String get() = "${left.substringAfterLast('/')} ↔ ${right.substringAfterLast('/')}"

    val lastSummary: String
        get() = when {
            lastError != null -> lastError
            lastRunAt == 0L -> "not run yet"
            else -> buildString {
                append("$lastCopied copied")
                if (lastDeleted > 0) append(", $lastDeleted trashed")
                if (lastConflicts > 0) append(", $lastConflicts kept")
                if (lastFailed > 0) append(", $lastFailed failed")
            }
        }
}

@Serializable
data class SyncJobs(val items: List<SyncJob> = emptyList())

/** What one sync did, whichever side of the app asked for it. */
@androidx.compose.runtime.Immutable
data class SyncRunOutcome(
    val copied: Int = 0,
    val trashed: Int = 0,
    val failed: Int = 0,
    val conflicts: Int = 0,
    val error: String? = null,
)
