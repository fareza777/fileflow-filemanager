package com.fileflow.app.domain.sync

import com.fileflow.app.domain.compare.DiffRow
import com.fileflow.app.domain.compare.DiffState

/** Which way the sync runs. Chosen explicitly, never inferred from selection. */
enum class SyncDirection {
    LEFT_TO_RIGHT,
    RIGHT_TO_LEFT,
    TWO_WAY,
    ;

    val label: String
        get() = when (this) {
            LEFT_TO_RIGHT -> "left → right"
            RIGHT_TO_LEFT -> "right → left"
            TWO_WAY -> "both ways"
        }
}

/**
 * How far the sync goes.
 *
 * Incremental pushes what is missing and leaves the target's own files alone.
 * Mirror makes the target look exactly like the source, which means deleting
 * files that exist only there — the one operation in this screen that can lose
 * something, so it is a separate choice with its own confirmation.
 */
enum class SyncMode { INCREMENTAL, MIRROR }

/** What the sync will do, before it does any of it. */
@androidx.compose.runtime.Immutable
data class SyncPlan(
    val copyToRight: List<DiffRow> = emptyList(),
    val copyToLeft: List<DiffRow> = emptyList(),
    val deleteOnRight: List<DiffRow> = emptyList(),
    val deleteOnLeft: List<DiffRow> = emptyList(),
    /** Present on both sides and different: kept, never overwritten. */
    val conflicts: List<DiffRow> = emptyList(),
) {
    val copies: Int get() = copyToRight.size + copyToLeft.size
    val deletions: Int get() = deleteOnRight.size + deleteOnLeft.size
    val conflictsCount: Int get() = conflicts.size

    val copiesBytes: Long
        get() = copyToRight.sumOf { it.leftSize } + copyToLeft.sumOf { it.rightSize }

    val isEmpty: Boolean get() = copies == 0 && deletions == 0

    /** One line for the confirmation, in the order the work will happen. */
    fun summary(): String = buildString {
        if (copyToRight.isNotEmpty()) append("${copyToRight.size} → right")
        if (copyToLeft.isNotEmpty()) {
            if (isNotEmpty()) append(", ")
            append("${copyToLeft.size} → left")
        }
        if (deletions > 0) {
            if (isNotEmpty()) append(", ")
            append("$deletions deleted")
        }
        if (conflictsCount > 0) {
            if (isNotEmpty()) append(", ")
            append("$conflictsCount kept")
        }
        if (isEmpty()) append("nothing to do")
    }
}

/**
 * Turns a comparison into a plan.
 *
 * Pure on purpose: what a sync will do is the part that has to be right, and it
 * is much easier to be sure of a decision table than of a coroutine that copies
 * files while deciding. The screen shows the plan, the user approves it, and
 * only then does anything touch the disk.
 *
 * Two policy decisions are worth naming:
 *
 * - A file that exists on both sides and differs is **never overwritten** in
 *   either direction. Without a record of which side last changed — which this
 *   app deliberately does not keep — overwriting is a guess, and a guess here
 *   destroys the newer file half the time. Conflicts are reported and kept.
 * - A file that is only on the target side is left alone unless the mode is
 *   [SyncMode.MIRROR], because "you have a file I do not" is not a mistake
 *   unless the user says the folder should be an exact copy.
 */
object SyncPlanner {

    fun plan(
        rows: List<DiffRow>,
        direction: SyncDirection,
        mode: SyncMode,
    ): SyncPlan {
        val copyToRight = mutableListOf<DiffRow>()
        val copyToLeft = mutableListOf<DiffRow>()
        val deleteOnRight = mutableListOf<DiffRow>()
        val deleteOnLeft = mutableListOf<DiffRow>()
        val conflicts = mutableListOf<DiffRow>()

        rows.forEach { row ->
            when (row.state) {
                DiffState.SAME -> Unit

                DiffState.LEFT_ONLY -> when (direction) {
                    SyncDirection.LEFT_TO_RIGHT, SyncDirection.TWO_WAY -> copyToRight += row
                    // Mirroring right-to-left means the left side should look
                    // like the right one, so a left-only file is an extra.
                    SyncDirection.RIGHT_TO_LEFT -> if (mode == SyncMode.MIRROR) deleteOnLeft += row
                }

                DiffState.RIGHT_ONLY -> when (direction) {
                    SyncDirection.RIGHT_TO_LEFT, SyncDirection.TWO_WAY -> copyToLeft += row
                    SyncDirection.LEFT_TO_RIGHT -> if (mode == SyncMode.MIRROR) deleteOnRight += row
                }

                DiffState.DIFFERENT, DiffState.TYPE_MISMATCH -> when (direction) {
                    // One-way: the source side wins, because the user said so by
                    // pointing the arrow at it.
                    SyncDirection.LEFT_TO_RIGHT -> copyToRight += row
                    SyncDirection.RIGHT_TO_LEFT -> copyToLeft += row
                    // Both ways and different: keeping both is the only answer
                    // that cannot lose the newer version.
                    SyncDirection.TWO_WAY -> conflicts += row
                }
            }
        }

        return SyncPlan(
            copyToRight = copyToRight,
            copyToLeft = copyToLeft,
            deleteOnRight = deleteOnRight,
            deleteOnLeft = deleteOnLeft,
            conflicts = conflicts,
        )
    }
}
