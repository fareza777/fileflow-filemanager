package com.fileflow.app.domain.rules

import com.fileflow.app.core.util.Paths
import com.fileflow.app.domain.Candidate
import com.fileflow.app.domain.FileWalker
import com.fileflow.app.domain.WalkOptions
import com.fileflow.app.domain.rename.NameTemplate
import com.fileflow.app.domain.scan.StorageWalkGate
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Turns a rule into a concrete list of things that would happen, without
 * touching a single file. Every entry point that mutates storage goes through
 * a plan first, which is what lets the UI promise "preview before execution".
 */
@Singleton
class RuleEngine @Inject constructor(
    private val walker: FileWalker,
    private val gate: StorageWalkGate,
) {

    /**
     * Evaluates every rule in a single pass over storage.
     *
     * Rules used to each walk the whole filesystem on their own: five daily
     * rules meant five sweeps of the device, five sets of metadata reads, and a
     * phone that got warm for no reason. One walk, many rules.
     */
    fun planAll(
        rules: List<Rule>,
        roots: List<String>,
        now: Long = System.currentTimeMillis(),
        onScanned: (filesSeen: Int) -> Unit = {},
    ): Flow<RuleMatch> {
        if (rules.isEmpty()) return kotlinx.coroutines.flow.emptyFlow()

        val walkRoots = rules
            .flatMap { if (it.spec.roots.isEmpty()) roots else it.spec.roots }
            .distinct()
        // The strictest walk in the batch decides: a rule that skips hidden
        // files cannot be evaluated from a pass that skipped them earlier.
        val recursive = rules.any { it.spec.recursive }
        val skipHidden = rules.all { it.spec.skipHidden }

        val options = WalkOptions(
            recursive = recursive,
            skipHidden = skipHidden,
            includeDirectories = false,
        )

        return kotlinx.coroutines.flow.flow {
            // A rule run is a sweep of storage like any other; letting it share
            // the device with the home screen's scan made both slower than
            // either would have been alone.
            gate.run {
                var seen = 0
                val counts = HashMap<Long, Int>()
                walker.walkFiles(walkRoots, options).collect { candidate ->
                    seen++
                    if (seen % 200 == 0) onScanned(seen)
                    rules.forEach { rule ->
                        if (!withinRoots(candidate.path, rule, roots)) return@forEach
                        if (!RuleMatcher.matches(candidate, rule.spec, now)) return@forEach
                        val limit = rule.spec.maxFilesPerRun
                        val taken = counts[rule.id] ?: 0
                        if (limit in 1..taken) return@forEach
                        val operation = resolve(candidate, rule.spec) ?: return@forEach
                        counts[rule.id] = taken + 1
                        emit(RuleMatch(rule.id, operation))
                    }
                }
                onScanned(seen)
            }
        }
    }

    /**
     * A rule that asked not to descend has to be held to it. The batch walk is
     * recursive whenever a single rule wants depth, so without this check a
     * rule saved as "this folder only" quietly matched files buried in
     * subfolders — and moved them.
     *
     * A rule with no roots of its own is scoped to the roots the plan was made
     * against, not to the union of every other rule's roots, which is what the
     * walk actually covers.
     */
    private fun withinRoots(path: String, rule: Rule, allRoots: List<String>): Boolean {
        val scoped = if (rule.spec.roots.isEmpty()) allRoots else rule.spec.roots
        val parent = Paths.parentOf(path)?.trimEnd('/') ?: return false
        return scoped.any { root ->
            val trimmed = root.trimEnd('/')
            if (rule.spec.recursive) Paths.isInside(path, trimmed) else parent == trimmed
        }
    }

    /**
     * Streaming matches so a preview can render the first hits while the device
     * is still being walked. [onScanned] reports the walking progress, which
     * is the number a user actually waits on — matches are usually rare.
     */
    fun plan(
        spec: RuleSpec,
        roots: List<String>,
        now: Long = System.currentTimeMillis(),
        onScanned: (filesSeen: Int) -> Unit = {},
    ): Flow<PlannedOperation> {
        val effectiveRoots = if (spec.roots.isEmpty()) roots else spec.roots
        val options = WalkOptions(
            recursive = spec.recursive,
            skipHidden = spec.skipHidden,
            includeDirectories = false,
        )
        return kotlinx.coroutines.flow.flow {
            var seen = 0
            walker.walkFiles(effectiveRoots, options).collect { candidate ->
                seen++
                if (seen % 200 == 0) onScanned(seen)
                if (!RuleMatcher.matches(candidate, spec, now)) return@collect
                val operation = resolve(candidate, spec) ?: return@collect
                emit(operation)
            }
            onScanned(seen)
        }
    }

    /**
     * Works out where a matched file ends up. Returns null when the file is
     * already exactly where the rule would put it, which is how a rule stays
     * idempotent on a daily schedule.
     */
    fun resolve(candidate: Candidate, spec: RuleSpec): PlannedOperation? {
        val action = spec.action
        val parentPath = Paths.parentOf(candidate.path) ?: return null

        when (action.kind) {
            ActionKind.TRASH, ActionKind.DELETE -> return PlannedOperation(
                sourcePath = candidate.path,
                sourceName = candidate.name,
                destinationPath = null,
                destinationName = null,
                sizeBytes = candidate.sizeBytes,
                isDirectory = candidate.isDirectory,
                conflict = false,
            )

            ActionKind.RENAME -> {
                val pattern = action.renamePattern ?: return null
                val newName = renderName(pattern, candidate, parentPath)
                if (newName == candidate.name) return null
                if (!Paths.isValidFileName(newName)) return null
                val target = File(parentPath, newName)
                return PlannedOperation(
                    sourcePath = candidate.path,
                    sourceName = candidate.name,
                    destinationPath = target.absolutePath,
                    destinationName = newName,
                    sizeBytes = candidate.sizeBytes,
                    isDirectory = candidate.isDirectory,
                    conflict = target.exists() && target.absolutePath != candidate.path,
                )
            }

            ActionKind.MOVE, ActionKind.COPY -> {
                val destinationRoot = action.destination?.takeIf { it.isNotBlank() } ?: return null
                val subfolder = subfolderFor(action, candidate)
                val folderPath = if (subfolder.isNullOrEmpty()) {
                    destinationRoot
                } else {
                    Paths.join(destinationRoot, subfolder)
                }
                if (folderPath.trimEnd('/') == parentPath.trimEnd('/')) return null

                val newName = action.renamePattern
                    ?.takeIf { it.isNotBlank() }
                    ?.let { renderName(it, candidate, parentPath) }
                    ?: candidate.name
                val target = File(folderPath, newName)
                return PlannedOperation(
                    sourcePath = candidate.path,
                    sourceName = candidate.name,
                    destinationPath = target.absolutePath,
                    destinationName = newName,
                    sizeBytes = candidate.sizeBytes,
                    isDirectory = candidate.isDirectory,
                    conflict = target.exists(),
                )
            }
        }
    }

    private fun renderName(pattern: String, candidate: Candidate, parentPath: String): String {
        val extension = candidate.extension
        val rendered = NameTemplate.render(
            pattern = pattern,
            name = candidate.name,
            extension = extension,
            lastModified = candidate.lastModified,
            category = candidate.category.key,
            parentName = parentPath.substringAfterLast('/'),
        )
        val cleaned = Paths.sanitizeFileName(rendered)
        return if (extension.isNotEmpty() && !cleaned.endsWith(".$extension", ignoreCase = true)) {
            "$cleaned.$extension"
        } else {
            cleaned
        }
    }

    private fun subfolderFor(action: ActionSpec, candidate: Candidate): String? =
        when (action.subfolder) {
            SubfolderStrategy.NONE -> null
            SubfolderStrategy.BY_CATEGORY -> candidate.category.key.replaceFirstChar(Char::uppercase)
            SubfolderStrategy.BY_EXTENSION ->
                candidate.extension.uppercase(Locale.US).ifEmpty { "Other" }
            SubfolderStrategy.BY_MONTH -> MONTH_FORMAT.format(Instant.ofEpochMilli(candidate.lastModified))
        }

    private companion object {
        /**
         * One formatter for the process. Building a SimpleDateFormat per file
         * clones a Calendar and a date-symbol table every time, which a rule
         * running over thousands of files paid thousands of times.
         */
        val MONTH_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM", Locale.US).withZone(ZoneId.systemDefault())
    }

    /**
     * A one-line explanation used by the preview list and the rule card, so a
     * user never has to open the editor to remember what a rule does.
     */
    fun describe(spec: RuleSpec): String {
        val where = when (spec.action.kind) {
            ActionKind.MOVE -> "Move to ${spec.action.destination?.substringAfterLast('/') ?: "?"}"
            ActionKind.COPY -> "Copy to ${spec.action.destination?.substringAfterLast('/') ?: "?"}"
            ActionKind.TRASH -> "Move to trash"
            ActionKind.DELETE -> "Delete permanently"
            ActionKind.RENAME -> "Rename as ${spec.action.renamePattern ?: "?"}"
        }
        val conditions = spec.conditions.size
        val joiner = if (spec.matchMode == MatchMode.ALL) " and " else " or "
        val parts = buildList {
            spec.conditions.forEach { condition ->
                add(describeCondition(condition))
            }
        }
        val prefix = if (parts.isEmpty()) "Nothing" else parts.joinToString(joiner)
        val negatedSuffix = if (conditions == 0) "" else ""
        return "$prefix$negatedSuffix → $where"
    }

    fun describeCondition(condition: MatchCondition): String {
        val base = when (condition) {
            is MatchCondition.Name -> when (condition.mode) {
                NameMode.CONTAINS -> "name contains \"${condition.text}\""
                NameMode.STARTS_WITH -> "name starts with \"${condition.text}\""
                NameMode.ENDS_WITH -> "name ends with \"${condition.text}\""
                NameMode.WILDCARD -> "name matches ${condition.text}"
                NameMode.REGEX -> "name matches /${condition.text}/"
            }
            is MatchCondition.Extension ->
                "type is ${condition.extensions.joinToString("/") { it.uppercase() }}"
            is MatchCondition.Category ->
                "file kind is ${condition.categories.joinToString("/")}"
            is MatchCondition.SourceFolder ->
                "from ${condition.folders.joinToString("/") { it.substringAfterLast('/').ifEmpty { it } }}"
            is MatchCondition.Size -> buildString {
                append("size ")
                val min = condition.minBytes
                val max = condition.maxBytes
                when {
                    min != null && max != null -> append("between $min and $max bytes")
                    min != null -> append("over $min bytes")
                    max != null -> append("under $max bytes")
                    else -> append("any")
                }
            }
            is MatchCondition.Age -> buildString {
                append("age ")
                val older = condition.olderThanDays
                val newer = condition.newerThanDays
                when {
                    older != null && newer != null -> append("between ${newer}d and ${older}d")
                    older != null -> append("over ${older} days")
                    newer != null -> append("under ${newer} days")
                    else -> append("any")
                }
            }
        }
        return if (condition.negate) "NOT ($base)" else base
    }
}
