package com.rsplwe.esurfing.diagnostics

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.time.Instant

enum class RunMode { NORMAL, SENSITIVE }

enum class EvidenceKind { EVENT_JSONL, PORTAL_RAW, ZSM_RAW, CIPHER_RAW, XML_RAW }

/** Typed storage failures — never a silent fallback to an empty string. */
sealed class EvidenceError {
    /** Raw capture attempted while the run is in [RunMode.NORMAL]. */
    object SensitiveCaptureDisabled : EvidenceError()

    /** Single payload above the hard per-write limit; nothing is written. */
    data class PayloadTooLarge(val requested: Long, val max: Long) : EvidenceError()

    /** Accumulated run bytes would exceed the run quota; nothing is written. */
    data class RunQuotaExceeded(val requested: Long, val quota: Long, val used: Long) : EvidenceError()

    /** The atomic write (temp → fsync → rename) could not complete. */
    data class IoFailed(val reason: String) : EvidenceError()

    /** The redaction layer rejected the payload (threw); nothing further is written. */
    data class RedactionFailed(val reason: String) : EvidenceError()
}

sealed class WriteOutcome {
    data class Saved(val redacted: ManifestEntry?, val sensitive: ManifestEntry?) : WriteOutcome()
    data class Failed(val error: EvidenceError) : WriteOutcome()
}

data class EvidenceInput(
    val kind: EvidenceKind,
    val stage: String,
    val runId: String,
    val attemptId: String,
    val correlationId: String,
    val payload: ByteArray,
    /** When true (and the run is SENSITIVE) the original bytes go to `sensitive/`. */
    val captureSensitive: Boolean = true,
)

/**
 * Tiered, size-limited, permission-restricted evidence storage.
 *
 * Layout under the configurable [root] for each run:
 *   `root/<runId>/redacted/`  — always present, only redacted derivatives
 *   `root/<runId>/sensitive/` — created only in [RunMode.SENSITIVE], raw bytes
 *   `root/<runId>/manifest.json` — SHA-256 / sizes / truncation / IDs inventory
 *
 * Guarantees:
 *  - **Tiering**: NORMAL mode never creates `sensitive/` and refuses raw
 *    capture with [EvidenceError.SensitiveCaptureDisabled]; SENSITIVE mode
 *    writes the raw original first, then an independent redacted derivative —
 *    raw is never modified in place.
 *  - **Atomic writes**: every file (including manifest.json) is written via a
 *    temp file → `fsync` (FileChannel.force) → atomic rename, so a crash or a
 *    failed write never leaves a half-written file or overwrites existing raw.
 *  - **Limits**: per-file soft limit truncates (original total length kept in
 *    the manifest); per-write hard limit and per-run quota yield typed failures.
 *  - **Permissions**: directories 0700 and files 0600 via [PosixFilePermission]
 *    on POSIX; on Windows/bind mounts this is best-effort and recorded as a
 *    residual risk in the manifest.
 *
 * Out of scope by design: no delete commands, no auto-upload, no in-app
 * archive encryption / password management.
 */
class EvidenceStore(
    private val root: Path,
    private val redactor: EvidenceRedactor,
    private val maxFileBytes: Long,
    private val maxPayloadBytes: Long,
    private val runQuotaBytes: Long,
    private val retention: RetentionPolicy,
) {
    init {
        require(maxFileBytes > 0) { "maxFileBytes must be positive" }
        require(maxPayloadBytes >= maxFileBytes) { "maxPayloadBytes must be >= maxFileBytes" }
        require(runQuotaBytes > 0) { "runQuotaBytes must be positive" }
    }

    fun beginRun(runId: String, mode: RunMode): EvidenceRun {
        require(runId.isNotBlank()) { "runId must not be blank" }
        val runDir = root.resolve(runId)
        val redactedDir = runDir.resolve("redacted")
        val sensitiveDir = if (mode == RunMode.SENSITIVE) runDir.resolve("sensitive") else null
        val residual = mutableSetOf<String>()
        try {
            Files.createDirectories(runDir)
            applyDirPerms(runDir, residual)
            Files.createDirectories(redactedDir)
            applyDirPerms(redactedDir, residual)
            if (sensitiveDir != null) {
                Files.createDirectories(sensitiveDir)
                applyDirPerms(sensitiveDir, residual)
            }
        } catch (e: IOException) {
            throw IllegalStateException("cannot initialise evidence run under $root: ${e.message}", e)
        }
        residual.add(RESIDUAL_RISK_NOTE)
        return EvidenceRun(
            runId = runId,
            mode = mode,
            runDir = runDir,
            redactedDir = redactedDir,
            sensitiveDir = sensitiveDir,
            redactor = redactor,
            maxFileBytes = maxFileBytes,
            maxPayloadBytes = maxPayloadBytes,
            runQuotaBytes = runQuotaBytes,
            retention = retention,
            createdAtIso = Instant.now().toString(),
            residualRisks = residual,
            entries = mutableListOf(),
        ).also { it.persistManifest() }
    }

    companion object {
        const val RESIDUAL_RISK_NOTE =
            "unix_posix_permissions_0700_0600_are_best_effort_not_enforced_on_windows_or_bind_mounts"
        private const val DIR_PERMS = "rwx------"
        private const val FILE_PERMS = "rw-------"

        internal fun applyDirPerms(path: Path, residual: MutableSet<String>) {
            try {
                Files.setPosixFilePermissions(path, parsePerms(DIR_PERMS))
            } catch (e: UnsupportedOperationException) {
                residual.add("posix_permissions_not_enforced")
            } catch (e: IOException) {
                residual.add("posix_permissions_not_enforced")
            }
        }

        internal fun applyFilePerms(path: Path, residual: MutableSet<String>) {
            try {
                Files.setPosixFilePermissions(path, parsePerms(FILE_PERMS))
            } catch (e: UnsupportedOperationException) {
                residual.add("posix_permissions_not_enforced")
            } catch (e: IOException) {
                residual.add("posix_permissions_not_enforced")
            }
        }

        private fun parsePerms(symbolic: String): Set<PosixFilePermission> =
            PosixFilePermission.values().filter { p ->
                symbolic.contains(
                    when (p) {
                        PosixFilePermission.OWNER_READ -> 'r'
                        PosixFilePermission.OWNER_WRITE -> 'w'
                        PosixFilePermission.OWNER_EXECUTE -> 'x'
                        else -> ' ' // group/other perms are never granted
                    },
                )
            }.toSet()
    }
}

class EvidenceRun internal constructor(
    val runId: String,
    val mode: RunMode,
    val runDir: Path,
    val redactedDir: Path,
    val sensitiveDir: Path?,
    private val redactor: EvidenceRedactor,
    private val maxFileBytes: Long,
    private val maxPayloadBytes: Long,
    private val runQuotaBytes: Long,
    private val retention: RetentionPolicy,
    private val createdAtIso: String,
    private val residualRisks: MutableSet<String>,
    private val entries: MutableList<ManifestEntry>,
) {
    private var seq = 0L
    private var usedBytes = 0L

    fun write(input: EvidenceInput): WriteOutcome {
        val payload = input.payload
        if (payload.size.toLong() > maxPayloadBytes) {
            return WriteOutcome.Failed(EvidenceError.PayloadTooLarge(payload.size.toLong(), maxPayloadBytes))
        }
        val wantsSensitive = input.captureSensitive && input.kind != EvidenceKind.EVENT_JSONL
        if (wantsSensitive && mode == RunMode.NORMAL) {
            return WriteOutcome.Failed(EvidenceError.SensitiveCaptureDisabled)
        }

        val truncated = payload.size.toLong() > maxFileBytes
        val storedBytes: ByteArray = if (truncated) payload.copyOf(maxFileBytes.toInt()) else payload
        val originalLength = payload.size.toLong()

        // Compute the redacted derivative first (in memory) so a redaction
        // failure or a quota overflow rejects BEFORE any file is touched.
        val redactedBytes: ByteArray = try {
            redactor.redactBytes(storedBytes)
        } catch (e: Exception) {
            return WriteOutcome.Failed(EvidenceError.RedactionFailed(e.message ?: e::class.java.simpleName))
        }

        val n = ++seq
        val newBytes = redactedBytes.size.toLong() + (if (wantsSensitive && mode == RunMode.SENSITIVE) storedBytes.size.toLong() else 0L)
        if (usedBytes + newBytes > runQuotaBytes) {
            return WriteOutcome.Failed(EvidenceError.RunQuotaExceeded(newBytes, runQuotaBytes, usedBytes))
        }

        // 1. Write the raw original first (only in SENSITIVE mode).
        val sensitiveEntry: ManifestEntry? = if (wantsSensitive && mode == RunMode.SENSITIVE) {
            val fileName = "${baseName(input.kind)}-$n.bin"
            val outcome = atomicWrite(sensitiveDir!!.resolve(fileName), storedBytes)
            if (outcome.isFailure) {
                return WriteOutcome.Failed(
                    EvidenceError.IoFailed(outcome.exceptionOrNull()?.message ?: "atomic write failed"),
                )
            }
            ManifestEntry(
                tier = "sensitive", kind = input.kind.name, stage = input.stage,
                file = "sensitive/$fileName",
                sha256 = sha256(storedBytes),
                originalLength = originalLength, savedLength = storedBytes.size.toLong(),
                truncated = truncated,
                runId = input.runId, attemptId = input.attemptId, correlationId = input.correlationId,
                createdAtIso = Instant.now().toString(),
            )
        } else null

        // 2. Then the independent redacted derivative (raw is never modified).
        val redactedFileName = if (input.kind == EvidenceKind.EVENT_JSONL) {
            "${baseName(input.kind)}-$n.jsonl"
        } else {
            "${baseName(input.kind)}-$n.redacted"
        }
        val redactedOutcome = atomicWrite(redactedDir.resolve(redactedFileName), redactedBytes)
        if (redactedOutcome.isFailure) {
            return WriteOutcome.Failed(
                EvidenceError.IoFailed(redactedOutcome.exceptionOrNull()?.message ?: "atomic write failed"),
            )
        }
        val redactedEntry = ManifestEntry(
            tier = "redacted", kind = input.kind.name, stage = input.stage,
            file = "redacted/$redactedFileName",
            sha256 = sha256(redactedBytes),
            originalLength = originalLength, savedLength = redactedBytes.size.toLong(),
            truncated = truncated,
            runId = input.runId, attemptId = input.attemptId, correlationId = input.correlationId,
            createdAtIso = Instant.now().toString(),
        )

        entries.addAll(listOfNotNull(sensitiveEntry, redactedEntry))
        usedBytes += newBytes
        persistManifest()
        return WriteOutcome.Saved(redacted = redactedEntry, sensitive = sensitiveEntry)
    }

    fun finalizeManifest(): EvidenceManifest = buildManifest().also { persistManifest() }

    internal fun persistManifest() {
        val json = buildManifest().toJson().toByteArray()
        atomicWrite(runDir.resolve("manifest.json"), json)
    }

    private fun buildManifest(): EvidenceManifest = EvidenceManifest(
        schemaVersion = 1,
        runId = runId,
        mode = mode.name.lowercase(),
        createdAtIso = createdAtIso,
        retention = retention,
        residualRisks = residualRisks.sorted(),
        entries = entries.toList(),
    )

    private fun atomicWrite(target: Path, bytes: ByteArray): Result<Unit> {
        return try {
            val dir = target.parent
            Files.createDirectories(dir)
            EvidenceStore.applyDirPerms(dir, residualRisks)
            val temp = Files.createTempFile(dir, ".evidence-", ".tmp")
            FileChannel.open(temp, StandardOpenOption.WRITE).use { ch ->
                val buf = ByteBuffer.wrap(bytes)
                while (buf.hasRemaining()) ch.write(buf)
                ch.force(true) // fsync content + metadata before the rename
            }
            EvidenceStore.applyFilePerms(temp, residualRisks)
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            Result.success(Unit)
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: SecurityException) {
            Result.failure(e)
        }
    }

    private fun baseName(kind: EvidenceKind): String = when (kind) {
        EvidenceKind.EVENT_JSONL -> "event"
        EvidenceKind.PORTAL_RAW -> "portal-raw"
        EvidenceKind.ZSM_RAW -> "zsm-raw"
        EvidenceKind.CIPHER_RAW -> "cipher-raw"
        EvidenceKind.XML_RAW -> "xml-raw"
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
