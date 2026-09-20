package com.fileflow.app.domain.rules

import com.fileflow.app.data.db.RuleEntity
import kotlinx.serialization.json.Json

/**
 * Specs live in a single JSON column so the rule vocabulary can grow without a
 * migration each time. Decoding is total: a rule written by an older version
 * falls back to a safe default instead of crashing the rule list.
 */
object RuleJson {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    fun encode(spec: RuleSpec): String = json.encodeToString(RuleSpec.serializer(), spec)

    private fun attempt(raw: String?): Result<RuleSpec> =
        if (raw.isNullOrBlank()) {
            Result.success(RuleSpec())
        } else {
            runCatching { json.decodeFromString(RuleSpec.serializer(), raw) }
        }

    fun decode(raw: String?): RuleSpec = attempt(raw).getOrNull() ?: RuleSpec()

    fun toRule(entity: RuleEntity): Rule {
        // Decoding is still total — a bad row must not take the rule list down —
        // but the failure is now carried on the rule so the UI can say the rule
        // is not doing anything and why, rather than showing a rule that quietly
        // never runs.
        val decoded = attempt(entity.specJson)
        return Rule(
            id = entity.id,
            name = entity.name,
            enabled = entity.enabled,
            spec = decoded.getOrNull() ?: RuleSpec(),
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            lastRunAt = entity.lastRunAt,
            lastRunStatus = entity.lastRunStatus,
            lastMatchedCount = entity.lastMatchedCount,
            specUnreadable = decoded.isFailure,
        )
    }
}
