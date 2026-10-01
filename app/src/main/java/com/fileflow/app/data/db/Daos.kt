package com.fileflow.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * A move into the trash is described by two rows — one in the journal so it can
 * be reversed, one in the trash index so the user can see it. Written as two
 * separate statements, a process death in between left a file sitting in the
 * trash folder that the app could neither show nor restore. Both rows or
 * neither.
 */
@Dao
interface TrashJournalDao {

    @Insert
    suspend fun insertJournal(entity: JournalEntity): Long

    @Insert
    suspend fun insertTrash(entity: TrashEntity): Long

    @Transaction
    suspend fun recordTrashChange(journal: JournalEntity, trash: TrashEntity) {
        insertJournal(journal)
        insertTrash(trash)
    }
}

@Dao
interface RuleDao {

    @Query("SELECT * FROM rules WHERE isTemplate = 0 ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM rules WHERE isTemplate = 0")
    suspend fun all(): List<RuleEntity>

    @Query("SELECT * FROM rules WHERE isTemplate = 0 AND enabled = 1")
    suspend fun enabled(): List<RuleEntity>

    @Query("SELECT * FROM rules WHERE id = :id")
    suspend fun byId(id: Long): RuleEntity?

    @Query("SELECT * FROM rules WHERE id = :id")
    fun observe(id: Long): Flow<RuleEntity?>

    @Insert
    suspend fun insert(rule: RuleEntity): Long

    @Update
    suspend fun update(rule: RuleEntity)

    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun delete(id: Long)

    @Query(
        """
        UPDATE rules SET lastRunAt = :at, lastRunStatus = :status, lastMatchedCount = :matched
        WHERE id = :id
        """
    )
    suspend fun recordRun(id: Long, at: Long, status: String, matched: Int)

    @Query("UPDATE rules SET enabled = :enabled, updatedAt = :now WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, now: Long)
}

@Dao
interface RuleLogDao {

    @Query("SELECT * FROM rule_logs ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<RuleLogEntity>>

    @Query("SELECT * FROM rule_logs WHERE ruleId = :ruleId ORDER BY startedAt DESC LIMIT :limit")
    fun observeForRule(ruleId: Long, limit: Int = 50): Flow<List<RuleLogEntity>>

    @Query("SELECT * FROM rule_logs WHERE id = :id")
    suspend fun byId(id: Long): RuleLogEntity?

    @Query("SELECT * FROM rule_logs WHERE startedAt = (SELECT MAX(startedAt) FROM rule_logs)")
    suspend fun latest(): RuleLogEntity?

    @Query("SELECT MAX(startedAt) FROM rule_logs WHERE status != 'PREVIEW'")
    fun observeLastRunAt(): Flow<Long?>

    @Insert
    suspend fun insert(log: RuleLogEntity): Long

    @Query(
        """
        UPDATE rule_logs SET finishedAt = :finishedAt, status = :status, matched = :matched,
            succeeded = :succeeded, failed = :failed, bytesMoved = :bytes, note = :note
        WHERE id = :id
        """
    )
    suspend fun finish(
        id: Long,
        finishedAt: Long,
        status: String,
        matched: Int,
        succeeded: Int,
        failed: Int,
        bytes: Long,
        note: String? = null,
    )

    @Query("UPDATE rule_logs SET undoneAt = :at, status = 'UNDONE' WHERE id = :id")
    suspend fun markUndone(id: Long, at: Long)

    @Query("DELETE FROM rule_logs")
    suspend fun clear()
}

@Dao
interface TrashDao {

    @Query("SELECT * FROM trash ORDER BY deletedAt DESC")
    fun observeAll(): Flow<List<TrashEntity>>

    @Query("SELECT * FROM trash ORDER BY deletedAt DESC")
    suspend fun all(): List<TrashEntity>

    @Query("SELECT * FROM trash WHERE deletedAt < :cutoff")
    suspend fun olderThan(cutoff: Long): List<TrashEntity>

    /**
     * Undo looks up one trashed file at a time; doing it with a full-table read
     * made undoing a large run quadratic.
     */
    @Query("SELECT * FROM trash WHERE trashPath = :trashPath LIMIT 1")
    suspend fun byTrashPath(trashPath: String): TrashEntity?

    @Query("SELECT * FROM trash WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<TrashEntity>

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM trash")
    fun observeTotalSize(): Flow<Long>

    @Query("SELECT COUNT(*) FROM trash")
    fun observeCount(): Flow<Int>

    @Insert
    suspend fun insert(entity: TrashEntity): Long

    @Query("DELETE FROM trash WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM trash")
    suspend fun clear()
}

@Dao
interface JournalDao {

    @Query("SELECT * FROM journal WHERE groupId = :groupId ORDER BY id DESC")
    suspend fun forGroup(groupId: String): List<JournalEntity>

    @Query("SELECT * FROM journal WHERE groupId = :groupId AND undone = 0 ORDER BY id DESC")
    suspend fun undoableInGroup(groupId: String): List<JournalEntity>

    @Query("SELECT * FROM journal WHERE kind = 'RENAME' ORDER BY id DESC LIMIT :limit")
    fun observeRenames(limit: Int = 300): Flow<List<JournalEntity>>

    @Query(
        """
        SELECT * FROM journal WHERE groupId = :groupId AND kind = 'RENAME'
        ORDER BY id DESC LIMIT :limit
        """
    )
    suspend fun renamesInGroup(groupId: String, limit: Int = 500): List<JournalEntity>

    @Insert
    suspend fun insert(entity: JournalEntity): Long

    @Insert
    suspend fun insertAll(entities: List<JournalEntity>): List<Long>

    @Query("UPDATE journal SET undone = 1, undoError = :error WHERE id = :id")
    suspend fun markUndone(id: Long, error: String? = null)

    /**
     * A reversal that failed stays reversible. Marking it undone would retire
     * the entry for good and let the history claim a change was rolled back
     * when the file is still where the operation put it.
     */
    @Query("UPDATE journal SET undoError = :error WHERE id = :id")
    suspend fun markUndoFailed(id: Long, error: String)

    @Query("UPDATE journal SET undone = 1 WHERE groupId = :groupId")
    suspend fun markGroupUndone(groupId: String)

    @Query("SELECT DISTINCT groupId FROM journal WHERE kind = 'RENAME' ORDER BY id DESC LIMIT 1")
    suspend fun latestRenameGroup(): String?

    @Query("DELETE FROM journal WHERE createdAt < :cutoff AND undone = 1")
    suspend fun prune(cutoff: Long)

    /**
     * Groups recent changes by the batch they belonged to. Manual operations
     * used to be undoable only from a snackbar that lived for a few seconds;
     * this is what makes them recoverable later.
     */
    @Query(
        """
        SELECT groupId,
               MIN(createdAt) AS startedAt,
               COUNT(*) AS entryCount,
               SUM(CASE WHEN undone = 1 THEN 1 ELSE 0 END) AS undoneCount,
               SUM(sizeBytes) AS bytes,
               SUM(CASE WHEN kind = 'MOVE' THEN 1 ELSE 0 END) AS moves,
               SUM(CASE WHEN kind = 'COPY' THEN 1 ELSE 0 END) AS copies,
               SUM(CASE WHEN kind = 'TRASH' THEN 1 ELSE 0 END) AS trashes,
               SUM(CASE WHEN kind = 'RENAME' THEN 1 ELSE 0 END) AS renames
        FROM journal
        GROUP BY groupId
        ORDER BY startedAt DESC
        LIMIT :limit
        """
    )
    fun observeRecentGroups(limit: Int = 100): Flow<List<JournalGroupSummary>>

    @Query("SELECT COUNT(*) FROM journal WHERE undone = 0")
    fun observeUndoableCount(): Flow<Int>

    @Query("SELECT MIN(createdAt) FROM journal")
    suspend fun oldestEntryAt(): Long?
}

@Dao
interface ShortcutDao {

    @Query("SELECT * FROM shortcuts ORDER BY position ASC, addedAt ASC")
    fun observeAll(): Flow<List<ShortcutEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ShortcutEntity)

    @Query("DELETE FROM shortcuts WHERE path = :path")
    suspend fun delete(path: String)

    @Query("SELECT COUNT(*) FROM shortcuts")
    suspend fun count(): Int
}

@Dao
interface RecentFolderDao {

    @Query("SELECT * FROM recent_folders ORDER BY visitedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 12): Flow<List<RecentFolderEntity>>

    @Query("SELECT * FROM recent_folders WHERE path = :path")
    suspend fun byPath(path: String): RecentFolderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RecentFolderEntity)

    @Query("DELETE FROM recent_folders WHERE path NOT IN (SELECT path FROM recent_folders ORDER BY visitedAt DESC LIMIT 30)")
    suspend fun trim()
}

@Dao
interface FileIndexDao {

    @Query("SELECT COUNT(*) FROM indexed_files")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM indexed_files")
    suspend fun count(): Int

    /**
     * Existence only. `COUNT(*)` walks the whole table, and the search path
     * asked for it on every query just to decide whether the index was usable.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM indexed_files LIMIT 1)")
    suspend fun hasAny(): Boolean

    @Query("SELECT MAX(indexedAt) FROM indexed_files")
    fun observeIndexedAt(): Flow<Long?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<IndexedFileEntity>)

    @Query("DELETE FROM indexed_files WHERE path = :path")
    suspend fun remove(path: String)

    @Query("DELETE FROM indexed_files WHERE path LIKE :prefix || '%'")
    suspend fun removeUnder(prefix: String)

    @Query("DELETE FROM indexed_files")
    suspend fun clear()

    /**
     * The name filter is a query of its own.
     *
     * Wrapping it as `(:pattern = '' OR name LIKE :pattern)` disabled index use
     * for the entire statement, so even a search with no text in it — filtered
     * only by size or date — scanned every row in the index. When there is no
     * search term, the name predicate is simply not part of the query, which
     * lets the extension, size and modified indexes do their job.
     *
     * `root`/`deep` scope the same way the live walk does: `hasRoot = 0` means
     * the whole device; otherwise either the file sits directly in the folder
     * (`parentPath = root`) or, when deep, anywhere under it (`path LIKE root/%`).
     */
    @Query(
        """
        SELECT * FROM indexed_files
        WHERE name LIKE :pattern ESCAPE '\'
          AND (:hasRoot = 0 OR parentPath = :root OR (:deep = 1 AND path LIKE :rootPattern ESCAPE '\'))
          AND (:hasExtensions = 0 OR extension IN (:extensions))
          AND (:hasCategories = 0 OR extension IN (:categoryExtensions))
          AND (:minSize IS NULL OR sizeBytes >= :minSize)
          AND (:maxSize IS NULL OR sizeBytes <= :maxSize)
          AND (:modifiedAfter IS NULL OR modified >= :modifiedAfter)
          AND (:modifiedBefore IS NULL OR modified <= :modifiedBefore)
        ORDER BY modified DESC
        LIMIT :limit
        """
    )
    suspend fun searchByName(
        pattern: String,
        hasRoot: Int,
        root: String,
        deep: Int,
        rootPattern: String,
        hasExtensions: Int,
        extensions: List<String>,
        hasCategories: Int,
        categoryExtensions: List<String>,
        minSize: Long?,
        maxSize: Long?,
        modifiedAfter: Long?,
        modifiedBefore: Long?,
        limit: Int,
    ): List<IndexedFileEntity>

    @Query(
        """
        SELECT * FROM indexed_files
        WHERE (:hasRoot = 0 OR parentPath = :root OR (:deep = 1 AND path LIKE :rootPattern ESCAPE '\'))
          AND (:hasExtensions = 0 OR extension IN (:extensions))
          AND (:hasCategories = 0 OR extension IN (:categoryExtensions))
          AND (:minSize IS NULL OR sizeBytes >= :minSize)
          AND (:maxSize IS NULL OR sizeBytes <= :maxSize)
          AND (:modifiedAfter IS NULL OR modified >= :modifiedAfter)
          AND (:modifiedBefore IS NULL OR modified <= :modifiedBefore)
        ORDER BY modified DESC
        LIMIT :limit
        """
    )
    suspend fun searchFiltersOnly(
        hasRoot: Int,
        root: String,
        deep: Int,
        rootPattern: String,
        hasExtensions: Int,
        extensions: List<String>,
        hasCategories: Int,
        categoryExtensions: List<String>,
        minSize: Long?,
        maxSize: Long?,
        modifiedAfter: Long?,
        modifiedBefore: Long?,
        limit: Int,
    ): List<IndexedFileEntity>

    @Query(
        """
        SELECT * FROM indexed_files
        ORDER BY modified DESC
        LIMIT :limit
        """
    )
    suspend fun mostRecent(limit: Int): List<IndexedFileEntity>

    /**
     * The newest real files, for the recent feed. Zero-byte entries are skipped
     * because a placeholder is not "the file that just arrived".
     */
    @Query(
        """
        SELECT * FROM indexed_files
        WHERE isDirectory = 0 AND sizeBytes > 0
        ORDER BY modified DESC
        LIMIT :limit
        """
    )
    suspend fun mostRecentFiles(limit: Int): List<IndexedFileEntity>

    @Query("SELECT * FROM indexed_files WHERE parentPath = :parent ORDER BY name ASC")
    suspend fun childrenOf(parent: String): List<IndexedFileEntity>
}

@Dao
interface StarredFileDao {

    @Query("SELECT * FROM starred_files ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<StarredFileEntity>>

    @Query("SELECT * FROM starred_files ORDER BY addedAt DESC")
    suspend fun all(): List<StarredFileEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM starred_files WHERE path = :path)")
    suspend fun isStarred(path: String): Boolean

    @Query("SELECT path FROM starred_files")
    fun observePaths(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: StarredFileEntity)

    @Query("DELETE FROM starred_files WHERE path = :path")
    suspend fun remove(path: String)

    @Query("UPDATE starred_files SET path = :newPath, name = :newName WHERE path = :oldPath")
    suspend fun rename(oldPath: String, newPath: String, newName: String)

    @Query("SELECT COUNT(*) FROM starred_files")
    suspend fun count(): Int
}

@Dao
interface ContentIndexDao {

    @Query("SELECT COUNT(*) FROM content_index")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM content_index")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM content_vectors")
    suspend fun vectorCount(): Int

    @Query("SELECT indexedAt FROM content_index ORDER BY indexedAt DESC LIMIT 1")
    suspend fun indexedAt(): Long?

    @Query("DELETE FROM content_index")
    suspend fun clear()

    @Query("DELETE FROM content_vectors")
    suspend fun clearVectors()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entries: List<ContentIndexEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVectors(entries: List<ContentVectorEntity>)

    @Query("DELETE FROM content_index WHERE path = :path")
    suspend fun remove(path: String)

    @Query("DELETE FROM content_vectors WHERE path = :path")
    suspend fun removeVector(path: String)

    /**
     * The candidate scan. Every term has to appear somewhere in the folded
     * body; the caller then confirms the same terms and extracts the snippet,
     * because SQL cannot say where a hit was without an FTS index.
     */
    @Query(
        """
        SELECT * FROM content_index
        WHERE (:t1 = '' OR folded LIKE :t1)
          AND (:t2 = '' OR folded LIKE :t2)
          AND (:t3 = '' OR folded LIKE :t3)
          AND (:t4 = '' OR folded LIKE :t4)
        ORDER BY modified DESC
        LIMIT :limit
        """
    )
    suspend fun search(
        t1: String,
        t2: String,
        t3: String,
        t4: String,
        limit: Int,
    ): List<ContentIndexEntity>

    @Query("SELECT * FROM content_vectors")
    suspend fun allVectors(): List<ContentVectorEntity>

    @Query("SELECT * FROM content_index WHERE path = :path")
    suspend fun entry(path: String): ContentIndexEntity?

    @Query("SELECT * FROM content_index ORDER BY modified DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ContentIndexEntity>
}

@Dao
interface PhotoIndexDao {

    @Query("SELECT COUNT(*) FROM photo_index")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM photo_index")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM photo_index WHERE text != ''")
    suspend fun countWithText(): Int

    @Query("SELECT indexedAt FROM photo_index ORDER BY indexedAt DESC LIMIT 1")
    suspend fun indexedAt(): Long?

    @Query("DELETE FROM photo_index")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entries: List<PhotoIndexEntity>)

    @Query("SELECT path, modified, sizeBytes FROM photo_index")
    suspend fun fingerprints(): List<PhotoFingerprint>

    @Query("DELETE FROM photo_index WHERE path = :path")
    suspend fun remove(path: String)

    /** Photos whose *text* contains every term. */
    @Query(
        """
        SELECT * FROM photo_index
        WHERE (:t1 = '' OR folded LIKE :t1)
          AND (:t2 = '' OR folded LIKE :t2)
          AND (:t3 = '' OR folded LIKE :t3)
        ORDER BY modified DESC
        LIMIT :limit
        """
    )
    suspend fun searchText(
        t1: String,
        t2: String,
        t3: String,
        limit: Int,
    ): List<PhotoIndexEntity>

    /** Photos carrying any of the model labels a theme maps to. */
    @Query(
        """
        SELECT * FROM photo_index
        WHERE labels LIKE :l1 OR labels LIKE :l2 OR labels LIKE :l3
           OR labels LIKE :l4 OR labels LIKE :l5 OR labels LIKE :l6
           OR labels LIKE :l7 OR labels LIKE :l8 OR labels LIKE :l9
           OR labels LIKE :l10 OR labels LIKE :l11 OR labels LIKE :l12
        ORDER BY modified DESC
        LIMIT :limit
        """
    )
    suspend fun searchLabels(
        l1: String,
        l2: String,
        l3: String,
        l4: String,
        l5: String,
        l6: String,
        l7: String,
        l8: String,
        l9: String,
        l10: String,
        l11: String,
        l12: String,
        limit: Int,
    ): List<PhotoIndexEntity>

    @Query("SELECT * FROM photo_index ORDER BY modified DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<PhotoIndexEntity>
}
