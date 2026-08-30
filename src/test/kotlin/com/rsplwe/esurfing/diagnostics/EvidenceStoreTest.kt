package com.rsplwe.esurfing.diagnostics

import com.rsplwe.esurfing.fixture.CanarySecrets
import com.rsplwe.esurfing.fixture.FixtureSecretScanner
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behaviour contract of the tiered [EvidenceStore]:
 *  - NORMAL mode never creates `sensitive/` content and refuses raw capture
 *  - SENSITIVE mode writes raw + independent redacted derivative + manifest
 *  - every manifest SHA-256 is reproducible by recomputing the file
 *  - oversized input is truncated while the original total length is kept
 *  - hard limits, quota and I/O/redaction failures yield typed failures and
 *    never corrupt or overwrite already-written raw evidence
 */
class EvidenceStoreTest {

    @TempDir
    lateinit var tempDir: Path

    private fun store(
        root: Path = tempDir,
        redactor: EvidenceRedactor = Redactor.standard(),
        maxFileBytes: Long = 256,
        maxPayloadBytes: Long = 1024,
        runQuotaBytes: Long = 1_000_000,
        retention: RetentionPolicy = RetentionPolicy(maxRuns = 30, maxAgeDays = 90),
    ) = EvidenceStore(root, redactor, maxFileBytes, maxPayloadBytes, runQuotaBytes, retention)

    private fun sha256(file: Path): String =
        MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(file))
            .joinToString("") { "%02x".format(it) }

    private fun eventInput(
        payload: ByteArray,
        runId: String = "run",
        attemptId: String = "a1",
        correlationId: String = "c1",
        stage: String = "login",
        captureSensitive: Boolean = false,
    ) = EvidenceInput(EvidenceKind.EVENT_JSONL, stage, runId, attemptId, correlationId, payload, captureSensitive)

    private fun portalInput(
        payload: ByteArray,
        runId: String = "run",
        captureSensitive: Boolean = true,
    ) = EvidenceInput(EvidenceKind.PORTAL_RAW, "portal", runId, "a1", "c1", payload, captureSensitive)

    // --- NORMAL mode ---

    @Test
    fun normalMode_createsNoSensitiveContent() {
        val run = store().beginRun("run-normal", RunMode.NORMAL)
        assertNull(run.sensitiveDir, "NORMAL mode must not create a sensitive directory")
        assertFalse(Files.exists(run.runDir.resolve("sensitive")), "NORMAL mode must not create sensitive/")
        val canary = """{"userName":"zhangsan","password":"${CanarySecrets.CANARY_PASSWORD_DEMO}"}"""
        val out = run.write(eventInput(canary.toByteArray()))
        assertTrue(out is WriteOutcome.Saved, "a NORMAL redacted write must succeed")
        val saved = out as WriteOutcome.Saved
        assertNotNull(saved.redacted)
        assertNull(saved.sensitive, "NORMAL mode must never emit a sensitive entry")
        val hits = FixtureSecretScanner.scanDirectory(run.redactedDir.toFile())
        assertTrue(hits.isEmpty(), "redacted output must not leak canary, got: ${hits.joinToString()}")
    }

    @Test
    fun normalMode_sensitiveWrite_returnsTypedFailure() {
        val run = store().beginRun("run-normal-reject", RunMode.NORMAL)
        val out = run.write(portalInput("raw portal body".toByteArray()))
        assertTrue(out is WriteOutcome.Failed, "raw capture must be refused in NORMAL mode")
        assertTrue(
            (out as WriteOutcome.Failed).error is EvidenceError.SensitiveCaptureDisabled,
            "expected SensitiveCaptureDisabled"
        )
        assertFalse(Files.exists(run.runDir.resolve("sensitive")), "no sensitive/ may be created")
    }

    // --- SENSITIVE mode ---

    @Test
    fun sensitiveMode_writesRawAndRedactedAsIndependentFiles() {
        val run = store().beginRun("run-sens", RunMode.SENSITIVE)
        val payload = """{"userName":"zhangsan","password":"${CanarySecrets.CANARY_PASSWORD_DEMO}",""" +
            """"ticket":"${CanarySecrets.CANARY_TICKET_DEMO}"}"""
        val out = run.write(portalInput(payload.toByteArray(), runId = "run-sens"))
        assertTrue(out is WriteOutcome.Saved, "SENSITIVE write must succeed")
        val saved = out as WriteOutcome.Saved
        assertNotNull(saved.sensitive, "raw entry must exist")
        assertNotNull(saved.redacted, "redacted derivative must exist")
        assertNotEquals(saved.sensitive!!.file, saved.redacted!!.file, "raw and redacted must be distinct files")

        val rawFile = run.runDir.resolve(saved.sensitive!!.file)
        val redFile = run.runDir.resolve(saved.redacted!!.file)
        assertTrue(Files.exists(rawFile) && Files.exists(redFile))
        assertTrue(rawFile.toFile().readText().contains(CanarySecrets.CANARY_PASSWORD_DEMO), "raw keeps the original bytes")
        assertFalse(redFile.toFile().readText().contains(CanarySecrets.CANARY_PASSWORD_DEMO), "redacted removes secrets")
        assertTrue(FixtureSecretScanner.scanFile(redFile.toFile()).isEmpty(), "redacted derivative must be clean")
    }

    @Test
    fun manifest_entriesSha256_matchRecomputedFiles() {
        val run = store().beginRun("run-manifest", RunMode.SENSITIVE)
        run.write(portalInput("raw portal A".toByteArray(), runId = "run-manifest"))
        run.write(eventInput("""{"stage":"login","ok":true}""".toByteArray(), runId = "run-manifest"))
        val manifest = run.finalizeManifest()
        assertTrue(manifest.entries.size >= 2, "expected at least two manifest entries")
        for (entry in manifest.entries) {
            val file = run.runDir.resolve(entry.file)
            assertTrue(Files.exists(file), "manifest file must exist: ${entry.file}")
            assertEquals(entry.sha256, sha256(file), "recomputed SHA-256 must match manifest for ${entry.file}")
            assertEquals(Files.size(file), entry.savedLength, "saved length must match file size")
        }
        val manifestFile = run.runDir.resolve("manifest.json")
        assertTrue(Files.exists(manifestFile), "manifest.json must be written under the run directory")
        val onDisk = EvidenceManifest.fromJson(manifestFile.toFile().readText())
        assertEquals(manifest.entries.size, onDisk.entries.size, "on-disk manifest must be current")
    }

    @Test
    fun manifest_roundTripsThroughJson() {
        val run = store().beginRun("run-roundtrip", RunMode.SENSITIVE)
        run.write(portalInput("raw portal B".toByteArray(), runId = "run-roundtrip"))
        val manifest = run.finalizeManifest()
        val parsed = EvidenceManifest.fromJson(manifest.toJson())
        assertEquals(manifest.runId, parsed.runId)
        assertEquals(manifest.mode, parsed.mode)
        assertEquals(manifest.entries.size, parsed.entries.size)
        assertEquals(manifest.entries.first().sha256, parsed.entries.first().sha256)
        assertEquals(manifest.retention.maxRuns, parsed.retention.maxRuns)
    }

    // --- limits ---

    @Test
    fun oversizedInput_isTruncatedAndKeepsOriginalLength() {
        val run = store(maxFileBytes = 32).beginRun("run-trunc", RunMode.NORMAL)
        val payload = "x".repeat(100).toByteArray()
        val out = run.write(eventInput(payload))
        assertTrue(out is WriteOutcome.Saved, "oversized but under hard-limit input must be saved truncated")
        val entry = (out as WriteOutcome.Saved).redacted!!
        assertTrue(entry.truncated, "entry must be flagged truncated")
        assertEquals(100L, entry.originalLength, "original total length must be kept")
        assertEquals(32L, entry.savedLength, "saved length must equal the file limit")
        assertEquals(32L, Files.size(run.runDir.resolve(entry.file)), "on-disk file must be the truncated size")
    }

    @Test
    fun oversizedPayload_returnsTypedFailureAndRawNotOverwritten() {
        // maxFileBytes < maxPayloadBytes keeps the hard limit above the soften one.
        val run = store(maxFileBytes = 32, maxPayloadBytes = 64).beginRun("run-big", RunMode.SENSITIVE)
        val first = "first-payload-AAAA".toByteArray()
        val ok = run.write(portalInput(first, runId = "run-big"))
        assertTrue(ok is WriteOutcome.Saved)
        val rawFile = run.runDir.resolve((ok as WriteOutcome.Saved).sensitive!!.file)
        val rawBefore = rawFile.toFile().readBytes().copyOf()

        val oversized = ByteArray(500) { 'z'.code.toByte() }
        val out = run.write(portalInput(oversized, runId = "run-big"))
        assertTrue(out is WriteOutcome.Failed, "payload above the hard limit must be rejected")
        assertTrue((out as WriteOutcome.Failed).error is EvidenceError.PayloadTooLarge, "expected PayloadTooLarge")

        val sensFiles = run.sensitiveDir!!.toFile().listFiles()!!.filter { it.isFile }
        assertEquals(1, sensFiles.size, "no extra raw file may be written for the rejected payload")
        assertContentEquals(rawBefore, sensFiles.single().readBytes(), "existing raw must not be overwritten")
    }

    @Test
    fun runQuotaExceeded_returnsTypedFailure() {
        val run = store(runQuotaBytes = 100).beginRun("run-quota", RunMode.NORMAL)
        assertTrue(run.write(eventInput(ByteArray(60) { 'a'.code.toByte() })) is WriteOutcome.Saved)
        val out = run.write(eventInput(ByteArray(60) { 'b'.code.toByte() }))
        assertTrue(out is WriteOutcome.Failed, "run quota overflow must be rejected")
        assertTrue((out as WriteOutcome.Failed).error is EvidenceError.RunQuotaExceeded, "expected RunQuotaExceeded")
    }

    // --- failure / atomicity ---

    @Test
    fun diskUnwritable_returnsTypedFailureWithoutCorruptingRaw() {
        val run = store().beginRun("run-disk", RunMode.SENSITIVE)
        val ok = run.write(portalInput("first-raw".toByteArray(), runId = "run-disk"))
        assertTrue(ok is WriteOutcome.Saved)
        val firstEntry = (ok as WriteOutcome.Saved).sensitive!!
        val rawFile = run.runDir.resolve(firstEntry.file)

        // Block the exact name the next write would use with a directory, so the
        // atomic rename cannot complete — simulating an unwritable target path.
        val existing = run.sensitiveDir!!.toFile().listFiles()!!.count { it.isFile }
        val nextName = "portal-raw-${existing + 1}.bin"
        Files.createDirectory(run.sensitiveDir!!.resolve(nextName))

        val out = run.write(portalInput("second-raw".toByteArray(), runId = "run-disk"))
        assertTrue(out is WriteOutcome.Failed, "unwritable target must produce a typed failure")
        assertTrue((out as WriteOutcome.Failed).error is EvidenceError.IoFailed, "expected IoFailed")
        assertContentEquals("first-raw".toByteArray(), rawFile.toFile().readBytes(), "existing raw must stay intact")
    }

    @Test
    fun redactionFailure_returnsTypedFailureAndRawNotOverwritten() {
        val selective = object : EvidenceRedactor {
            override fun redactText(text: String): String =
                if (text.contains("BOOM")) throw IllegalStateException("boom") else text
            override fun redactBytes(input: ByteArray): ByteArray {
                if (String(input, Charsets.UTF_8).contains("BOOM")) throw IllegalStateException("boom")
                return input.copyOf()
            }
        }
        val run = store(redactor = selective).beginRun("run-redact", RunMode.SENSITIVE)
        val ok = run.write(portalInput("first-raw".toByteArray(), runId = "run-redact"))
        assertTrue(ok is WriteOutcome.Saved)
        val firstEntry = (ok as WriteOutcome.Saved).sensitive!!
        val rawFile = run.runDir.resolve(firstEntry.file)
        val rawBefore = rawFile.toFile().readBytes().copyOf()

        val out = run.write(portalInput("second-with-BOOM".toByteArray(), runId = "run-redact"))
        assertTrue(out is WriteOutcome.Failed, "redaction failure must surface as typed failure")
        assertTrue((out as WriteOutcome.Failed).error is EvidenceError.RedactionFailed, "expected RedactionFailed")
        assertContentEquals(rawBefore, rawFile.toFile().readBytes(), "first raw must remain byte-identical")
    }

    // --- CI / repo safety ---

    @Test
    fun sensitiveData_neverWrittenToWorkingDirectory() {
        val cwd = Path.of(".").toAbsolutePath().normalize()
        fun sensitiveDirs(): Long =
            Files.walk(cwd, 2).use { s ->
                s.filter { it.fileName.toString() == "sensitive" }.count()
            }
        val before = sensitiveDirs()
        val isolated = tempDir.resolve("ci-root")
        val run = store(root = isolated).beginRun("run-ci", RunMode.SENSITIVE)
        run.write(portalInput("ci raw payload".toByteArray(), runId = "run-ci"))
        run.write(eventInput("""{"stage":"login"}""".toByteArray(), runId = "run-ci"))
        assertEquals(before, sensitiveDirs(), "sensitive evidence must never land in the working directory")
    }

    // --- permissions (POSIX) ---

    @Test
    fun directoriesAndFiles_getRestrictedPosixPermissions() {
        val run = store().beginRun("run-perms", RunMode.SENSITIVE)
        val saved = run.write(portalInput("raw perm probe".toByteArray(), runId = "run-perms"))
        assertTrue(saved is WriteOutcome.Saved, "SENSITIVE write must succeed for the permission probe")
        val ownerOnlyDir = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
        val ownerOnlyFile = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        val dirPerms = try {
            Files.getPosixFilePermissions(run.runDir)
        } catch (e: UnsupportedOperationException) {
            return // non-POSIX filesystem (e.g. some bind mounts / Windows): best-effort
        }
        assertEquals(ownerOnlyDir, dirPerms, "run directory must be 0700")
        assertEquals(ownerOnlyDir, Files.getPosixFilePermissions(run.redactedDir), "redacted dir must be 0700")
        assertEquals(ownerOnlyDir, Files.getPosixFilePermissions(run.sensitiveDir!!), "sensitive dir must be 0700")
        val entry = (saved as WriteOutcome.Saved).sensitive!!
        assertEquals(ownerOnlyFile, Files.getPosixFilePermissions(run.runDir.resolve(entry.file)), "raw file must be 0600")
        assertEquals(
            ownerOnlyFile,
            Files.getPosixFilePermissions(run.runDir.resolve("manifest.json")),
            "manifest must be 0600",
        )
    }

    // --- whole-output secret leak scan ---

    @Test
    fun redactedAndManifestOutput_containsNoCanarySecrets() {
        val run = store().beginRun("run-clean", RunMode.SENSITIVE)
        val secretJson =
            """{"userName":"zhangsan","password":"${CanarySecrets.CANARY_PASSWORD_DEMO}",""" +
                """"smsCode":"${CanarySecrets.CANARY_SMS_DEMO}","schoolId":"4400000001"}"""
        val portalOut = run.write(portalInput(secretJson.toByteArray(), runId = "run-clean"))
        run.write(eventInput("""{"stage":"login","attemptId":"a1","ticket":"${CanarySecrets.CANARY_TICKET_DEMO}"}""".toByteArray(), runId = "run-clean"))
        run.finalizeManifest()
        val redactedHits = FixtureSecretScanner.scanDirectory(run.redactedDir.toFile())
        assertTrue(redactedHits.isEmpty(), "redacted/ must not leak canaries, got: ${redactedHits.joinToString()}")
        val manifestHits = FixtureSecretScanner.scanFile(run.runDir.resolve("manifest.json").toFile())
        assertTrue(manifestHits.isEmpty(), "manifest.json must not leak canaries, got: ${manifestHits.joinToString()}")
        val rawFile = run.runDir.resolve((portalOut as WriteOutcome.Saved).sensitive!!.file)
        val rawHitsKeepingRaw = FixtureSecretScanner.scanFile(rawFile.toFile())
        assertFalse(
            rawHitsKeepingRaw.isEmpty(),
            "sanity: the raw tier retains the canary, so the scan is live and redacted/ really removed it",
        )
    }
}
