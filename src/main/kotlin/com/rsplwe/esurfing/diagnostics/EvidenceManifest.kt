package com.rsplwe.esurfing.diagnostics

import com.google.gson.Gson
import com.google.gson.GsonBuilder

/** Configurable retention policy; deletion is deliberately left to humans. */
data class RetentionPolicy(
    val maxRuns: Int,
    val maxAgeDays: Int,
)

/** One written evidence file described in the manifest. */
data class ManifestEntry(
    val tier: String,
    val kind: String,
    val stage: String,
    val file: String,
    val sha256: String,
    val originalLength: Long,
    val savedLength: Long,
    val truncated: Boolean,
    val runId: String,
    val attemptId: String,
    val correlationId: String,
    val createdAtIso: String,
)

/**
 * Machine-readable inventory of one diagnostics run: run identity, mode, the
 * retention policy in force, residual risks (e.g. POSIX permissions are not
 * enforced on Windows / bind mounts) and one entry per written evidence file
 * carrying size, SHA-256, truncation and correlation IDs.
 */
data class EvidenceManifest(
    val schemaVersion: Int,
    val runId: String,
    val mode: String,
    val createdAtIso: String,
    val retention: RetentionPolicy,
    val residualRisks: List<String>,
    val entries: List<ManifestEntry>,
) {
    fun toJson(): String = PRETTY_GSON.toJson(this)

    companion object {
        private val PRETTY_GSON: Gson = GsonBuilder().setPrettyPrinting().create()
        private val GSON: Gson = Gson()

        fun fromJson(json: String): EvidenceManifest = GSON.fromJson(json, EvidenceManifest::class.java)
    }
}
