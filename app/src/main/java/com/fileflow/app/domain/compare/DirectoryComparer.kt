package com.fileflow.app.domain.compare

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** How one path differs between the two sides. */
enum class DiffState { SAME, LEFT_ONLY, RIGHT_ONLY, DIFFERENT, TYPE_MISMATCH }

/** One row of the comparison, keyed by its path relative to both roots. */
@androidx.compose.runtime.Immutable
data class DiffRow(
    val relativePath: String,
    val name: String,
    val isDirectory: Boolean,
    val state: DiffState,
    val leftSize: Long = 0L,
    val rightSize: Long = 0L,
    val leftModified: Long = 0L,
    val rightModified: Long = 0L,
    val leftPath: String? = null,
    val rightPath: String? = null,
)

data class CompareResult(
    val rows: List<DiffRow>,
    val scanned: Int,
) {
    val onlyLeft: Int get() = rows.count { it.state == DiffState.LEFT_ONLY }
    val onlyRight: Int get() = rows.count { it.state == DiffState.RIGHT_ONLY }
    val different: Int get() = rows.count { it.state == DiffState.DIFFERENT || it.state == DiffState.TYPE_MISMATCH }
    val identical: Int get() = rows.count { it.state == DiffState.SAME }
    val hasWork: Boolean get() = onlyLeft + onlyRight + different > 0
}

/**
 * Two folders, compared by relative path.
 *
 * The comparison is by path rather than by walking both trees in parallel: a
 * file that was renamed shows up as a delete plus an add, which is the honest
 * answer — anything cleverer would have to guess, and a wrong guess in a
 * synchronisation tool moves the wrong file.
 *
 * Size is the first test because it is free. Content is compared only when the
 * sizes match and the caller asked for it, and only under a ceiling: a
 * byte-by-byte pass over a 4 GB video is minutes of IO to answer a question
 * that "same size, same timestamp" already answered well enough.
 */
@Singleton
class DirectoryComparer @Inject constructor() {

    suspend fun compare(
        leftRoot: String,
        rightRoot: String,
        compareContent: Boolean = false,
        onProgress: (scanned: Int) -> Unit = {},
    ): CompareResult = withContext(Dispatchers.IO) {
        val left = File(leftRoot)
        val right = File(rightRoot)
        if (!left.isDirectory || !right.isDirectory) {
            return@withContext CompareResult(emptyList(), 0)
        }

        val leftIndex = index(left, onProgress)
        val rightIndex = index(right, onProgress)
        val paths = (leftIndex.keys + rightIndex.keys).sorted()

        val rows = mutableListOf<DiffRow>()
        paths.forEach { relative ->
            currentCoroutineContext().ensureActive()
            val leftFile = leftIndex[relative]
            val rightFile = rightIndex[relative]
            val name = relative.substringAfterLast('/')

            val state = when {
                leftFile == null -> DiffState.RIGHT_ONLY
                rightFile == null -> DiffState.LEFT_ONLY
                leftFile.isDirectory != rightFile.isDirectory -> DiffState.TYPE_MISMATCH
                leftFile.isDirectory -> DiffState.SAME
                else -> {
                    val sameSize = leftFile.length() == rightFile.length()
                    val same = sameSize && (
                        !compareContent ||
                            leftFile.length() > MAX_CONTENT_COMPARE ||
                            contentMatches(leftFile, rightFile)
                        )
                    if (same) DiffState.SAME else DiffState.DIFFERENT
                }
            }

            rows += DiffRow(
                relativePath = relative,
                name = name,
                isDirectory = (leftFile ?: rightFile)?.isDirectory == true,
                state = state,
                leftSize = leftFile?.length() ?: 0L,
                rightSize = rightFile?.length() ?: 0L,
                leftModified = leftFile?.lastModified() ?: 0L,
                rightModified = rightFile?.lastModified() ?: 0L,
                leftPath = leftFile?.absolutePath,
                rightPath = rightFile?.absolutePath,
            )
        }

        // Differences first, then paths: the list opens on the work to be done
        // rather than on two hundred identical files.
        CompareResult(
            rows = rows.sortedWith(
                compareBy<DiffRow> { if (it.state == DiffState.SAME) 1 else 0 }
                    .thenBy { it.relativePath.lowercase() }
            ),
            scanned = leftIndex.size + rightIndex.size,
        )
    }

    private suspend fun index(root: File, onProgress: (Int) -> Unit): Map<String, File> {
        val result = LinkedHashMap<String, File>()
        val queue = ArrayDeque<File>()
        queue += root
        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val folder = queue.removeFirst()
            val children = runCatching { folder.listFiles() }.getOrNull() ?: continue
            children.forEach { child ->
                if (child.name.startsWith(".")) return@forEach
                val relative = child.absolutePath
                    .removePrefix(root.absolutePath)
                    .trimStart(File.separatorChar)
                    .replace(File.separatorChar, '/')
                result[relative] = child
                if (child.isDirectory) queue += child
            }
            onProgress(result.size)
        }
        return result
    }

    /** A streaming byte comparison; the first difference ends it. */
    private fun contentMatches(left: File, right: File): Boolean {
        val leftStream = runCatching { left.inputStream() }.getOrNull() ?: return false
        val rightStream = runCatching { right.inputStream() }.getOrNull() ?: return false
        leftStream.use { a ->
            rightStream.use { b ->
                val bufferA = ByteArray(COMPARE_BUFFER)
                val bufferB = ByteArray(COMPARE_BUFFER)
                while (true) {
                    val readA = a.read(bufferA)
                    val readB = b.read(bufferB)
                    if (readA != readB) return false
                    if (readA <= 0) return true
                    for (index in 0 until readA) {
                        if (bufferA[index] != bufferB[index]) return false
                    }
                }
            }
        }
    }

    private companion object {
        const val COMPARE_BUFFER = 64 * 1024
        const val MAX_CONTENT_COMPARE = 64L * 1024 * 1024
    }
}
