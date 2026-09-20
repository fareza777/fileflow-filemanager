package com.fileflow.app.core.util

import android.content.Context
import com.fileflow.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One place that turns the outcome of an operation into a sentence.
 *
 * Every screen used to phrase its own, and most of them counted only what
 * succeeded — so a batch where half the files were refused read as a clean
 * success. Saying "12 moved, 3 failed" is not a nicety: it is the difference
 * between the user knowing a file is still sitting where they left it, and the
 * user believing it is where they put it.
 */
@Singleton
class OutcomeMessages @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun copied(succeeded: Int, failed: Int): String =
        withFailures(R.plurals.outcome_copied, succeeded, failed)

    fun moved(succeeded: Int, failed: Int): String =
        withFailures(R.plurals.outcome_moved, succeeded, failed)

    fun renamed(succeeded: Int, failed: Int): String =
        withFailures(R.plurals.outcome_renamed, succeeded, failed)

    fun restored(succeeded: Int, failed: Int): String =
        withFailures(R.plurals.outcome_restored, succeeded, failed)

    fun removed(succeeded: Int, failed: Int): String =
        withFailures(R.plurals.outcome_removed, succeeded, failed)

    fun deleted(succeeded: Int, failed: Int, toTrash: Boolean): String = withFailures(
        if (toTrash) R.plurals.outcome_moved_to_trash else R.plurals.outcome_deleted,
        succeeded,
        failed,
    )

    /**
     * Undo is where a partial result has to be loudest. The user believes the
     * change is gone; for the ones that failed, it is not, and the journal keeps
     * offering them until they work.
     */
    fun reversed(restored: Int, failed: Int, skipped: Int): String {
        val total = restored + failed + skipped
        return when {
            failed > 0 -> context.resources.getQuantityString(
                R.plurals.outcome_reversed_partial,
                failed,
                restored,
                total,
            )
            restored == 0 -> context.getString(R.string.outcome_nothing_changed)
            else -> context.getString(R.string.outcome_all_reversed)
        }
    }

    /**
     * Emptying the trash is reported as a whole rather than as a count: the
     * only thing worth saying is whether anything survived. Saying "emptied"
     * while files are still on disk is how storage quietly disappears.
     */
    fun emptied(deleted: Int, failed: Int): String = when {
        deleted == 0 && failed == 0 -> context.getString(R.string.trash_nothing_to_empty)
        failed == 0 -> context.getString(R.string.trash_emptied)
        else -> withFailures(R.plurals.outcome_deleted, deleted, failed)
    }

    private fun withFailures(plural: Int, succeeded: Int, failed: Int): String {
        val done = context.resources.getQuantityString(plural, succeeded, succeeded)
        if (failed <= 0) return done
        val problems = context.resources.getQuantityString(
            R.plurals.outcome_failed,
            failed,
            failed,
        )
        return context.getString(R.string.outcome_with_failures, done, problems)
    }
}
