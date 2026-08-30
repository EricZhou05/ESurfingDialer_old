package com.rsplwe.esurfing.docker

import com.rsplwe.esurfing.DiagnosticsRunner
import com.rsplwe.esurfing.Options
import com.rsplwe.esurfing.ShutdownHandler
import com.rsplwe.esurfing.diagnostics.EvidenceInput
import com.rsplwe.esurfing.diagnostics.EvidenceKind
import com.rsplwe.esurfing.diagnostics.EvidenceManifest
import com.rsplwe.esurfing.diagnostics.EvidenceStore
import com.rsplwe.esurfing.diagnostics.Redactor
import com.rsplwe.esurfing.diagnostics.RetentionPolicy
import com.rsplwe.esurfing.diagnostics.RunMode
import com.rsplwe.esurfing.fixture.AuthScenario
import com.rsplwe.esurfing.fixture.CanarySecrets
import com.rsplwe.esurfing.fixture.FixtureLoader
import com.rsplwe.esurfing.fixture.FixtureSecretScanner
import com.rsplwe.esurfing.fixture.TestAuthFlow
import com.rsplwe.esurfing.network.NetDiagnostics
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Local bind-mount equivalence validation for the container packaging (Todo 8).
 *
 * On a host WITHOUT a docker daemon we cannot run `docker build/run/inspect`.
 * Instead this suite treats one [TempDir] as the host-side bind mount that will
 * be `/data/diagnostics` inside the container, and proves the same persistence /
 * permissions / manifest-hash / redacted-only-export / canary-clean contract that
 * the container relies on:
 *
 *  1. two independent "container restarts" writing into the SAME mount directory
 *     keep every previous evidence run (persistence), with distinct runId/attemptId;
 *  2. evidence dirs/files carry POSIX 0700/0600 (best-effort on non-POSIX mounts);
 *  3. SHA-256 recomputed "from the host side" (outside the runner's memory) matches
 *     every manifest entry;
 *  4. a redacted-only export (manifest `tier == "redacted"`) excludes every
 *     `sensitive/` file, and the exported content is canary-clean;
 *  5. normal-mode evidence output is canary-clean end to end.
 *
 * All credentials are fictitious canaries from [CanarySecrets] — never a real
 * account. No file may be deleted by this suite.
 */
class DockerBindMountEquivalenceTest {

    /** Host-side directory that plays the role of the `-v ...:/data/diagnostics` bind mount. */
    @TempDir
    lateinit var mount: Path

    private val zsm: ByteArray = FixtureLoader.bytes("zsm/valid.zsm")

    @BeforeEach
    fun setUp() {
        NetDiagnostics.resetForTest()
    }

    private fun options(smsCode: String = CanarySecrets.CANARY_SMS_DEMO): Options =
        Options(CanarySecrets.CANARY_USER_DEMO, CanarySecrets.CANARY_PASSWORD_DEMO, smsCode)

    private fun ticketXml(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <response><result>success</result>
            <ticket>FIXTURE_TICKET_DOCKER</ticket>
        </response>
    """.trimIndent()

    private fun loginXml(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <response><result>success</result>
            <keep-url>${TestAuthFlow.KEEP_URL_TOKEN}/keep</keep-url>
            <term-url>${TestAuthFlow.TERM_URL_TOKEN}/term</term-url>
            <keep-retry>30</keep-retry>
        </response>
    """.trimIndent()

    private fun termOk(): String =
        """<?xml version="1.0" encoding="utf-8"?><response><result>success</result></response>"""

    /**
     * One full "container run": drives the real authorization flow against
     * MockWebServer, then a graceful shutdown (the SIGTERM equivalent) which
     * flushes the diagnostic events + manifest into [mount]. Each call resets
     * the in-process diagnostics context first — exactly like a fresh JVM /
     * fresh container: new run context, same bind mount.
     */
    private fun runFullContainerCycle(): StartedRun {
        NetDiagnostics.resetForTest()
        val session = TestAuthFlow.runSession(AuthScenario(options(), zsm, ticketXml(), loginXml()))
        val runner = DiagnosticsRunner.open(mount, mode = RunMode.NORMAL)
        session.enqueueEncrypted(termOk())
        ShutdownHandler(session.client, runner).shutdown()
        session.close()
        return StartedRun(runner.runId, readManifest(mount.resolve(runner.runId)))
    }

    private fun readManifest(runDir: Path): EvidenceManifest =
        EvidenceManifest.fromJson(Files.readString(runDir.resolve("manifest.json")))

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun assertDirPerms(dir: Path) {
        try {
            assertEquals(
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
                Files.getPosixFilePermissions(dir),
                "evidence directory $dir must be 0700",
            )
        } catch (e: UnsupportedOperationException) {
            return // best-effort: non-POSIX filesystem / bind mount
        }
    }

    private fun assertFilePerms(file: Path) {
        try {
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(file),
                "evidence file $file must be 0600",
            )
        } catch (e: UnsupportedOperationException) {
            return // best-effort: non-POSIX filesystem / bind mount
        }
    }

    // --- 1. persistence across restarts into the same mount directory ---

    @Test
    fun twoRestarts_intoSameMountDir_keepEveryPreviousRun_withDistinctIds() {
        val first = runFullContainerCycle()
        val second = runFullContainerCycle()

        // The first run's directory must still be on disk after the second restart.
        val firstDir = mount.resolve(first.runId)
        val secondDir = mount.resolve(second.runId)
        assertTrue(Files.isDirectory(firstDir), "first run dir must survive the second restart")
        assertTrue(Files.isDirectory(secondDir), "second run dir must exist")

        // Distinct run identity across restarts.
        assertNotEquals(first.runId, second.runId, "each container run gets a fresh runId")

        // Both manifests are complete and carry flushed events.
        val firstEvents = first.manifest.entries.filter { it.kind == "EVENT_JSONL" }
        val secondEvents = second.manifest.entries.filter { it.kind == "EVENT_JSONL" }
        assertTrue(firstEvents.isNotEmpty(), "first run manifest must contain EVENT_JSONL")
        assertTrue(secondEvents.isNotEmpty(), "second run manifest must contain EVENT_JSONL")
        assertNotEquals(
            firstEvents.first().attemptId,
            secondEvents.first().attemptId,
            "each run records a distinct attemptId on its events",
        )
    }

    // --- 2. permissions (POSIX best-effort; skipped on non-POSIX mount) ---

    @Test
    fun evidencePermissions_posixBestEffort_dirs0700_files0600() {
        val info = runFullContainerCycle()
        val runDir = mount.resolve(info.runId)
        assertDirPerms(runDir)
        assertDirPerms(runDir.resolve("redacted"))
        assertFilePerms(runDir.resolve("manifest.json"))

        val files = info.manifest.entries.map { runDir.resolve(it.file) } +
            listOf(runDir.resolve("manifest.json"))
        files.forEach(::assertFilePerms)
    }

    // --- 3. host-side SHA-256 recomputation matches every manifest entry ---

    @Test
    fun manifestHashes_recomputedOutsideRunner_matchEveryEntry() {
        val info = runFullContainerCycle()
        val runDir = mount.resolve(info.runId)
        assertTrue(info.manifest.entries.isNotEmpty(), "run must produce at least one evidence entry")
        for (entry in info.manifest.entries) {
            val actual = sha256Hex(Files.readAllBytes(runDir.resolve(entry.file)))
            assertEquals(
                entry.sha256,
                actual,
                "host-side SHA-256 of ${entry.file} must match the manifest (tier=${entry.tier})",
            )
        }
    }

    // --- 4. redacted-only export excludes sensitive/ and is canary-clean ---

    @Test
    fun sensitiveRun_redactedOnlyExport_excludesSensitive_andIsCanaryClean() {
        // Build a diagnostics run whose raw capture contains a canary (explicit
        // sensitive opt-in — not the default container mode). Real raw protocol
        // captures are XML documents, so the canary lives in a `<password>` tag —
        // exactly the shape Redactor.standard() must mask in the derived tier.
        val payload =
            """<?xml version="1.0"?><login><user>FIXTURE_USER_DOCKER</user><password>${CanarySecrets.CANARY_PASSWORD_DEMO}</password></login>"""
                .encodeToByteArray()
        val store = EvidenceStore(
            root = mount,
            redactor = Redactor.standard(),
            maxFileBytes = 1_000_000,
            maxPayloadBytes = 1_000_000,
            runQuotaBytes = 4L * 1024 * 1024,
            retention = RetentionPolicy(30, 90),
        )
        val run = store.beginRun("sensitive-fixture-managed", RunMode.SENSITIVE)
        run.write(
            EvidenceInput(
                kind = EvidenceKind.ZSM_RAW,
                stage = "fixture",
                runId = run.runId,
                attemptId = "att-sensitive-fixture",
                correlationId = "corr-sensitive-fixture",
                payload = payload,
                captureSensitive = true,
            ),
        )
        val manifest = run.finalizeManifest()

        val sensitive = manifest.entries.filter { it.tier == "sensitive" }
        val redacted = manifest.entries.filter { it.tier == "redacted" }
        val runDir = mount.resolve(run.runId)
        assertTrue(sensitive.isNotEmpty(), "sensitive mode must capture the raw tier")
        assertTrue(redacted.isNotEmpty(), "sensitive mode must also derive the redacted tier")

        // Sanity: the canary leaked into the raw capture (scanner WORKS), and the
        // redacted derivative is already clean.
        val rawFile = runDir.resolve(sensitive.first().file)
        assertTrue(
            FixtureSecretScanner.scanFile(rawFile.toFile()).isNotEmpty(),
            "scanner must detect the injected canary in the raw capture",
        )
        assertEquals(
            emptyList(),
            FixtureSecretScanner.scanFile(runDir.resolve(redacted.first().file).toFile()),
            "redacted derivative must mask the canary payload",
        )

        // A normal "export" is tier == "redacted" ONLY (explicitly excluding
        // sensitive/) — mirrored here as a copy to a host-side directory OUTSIDE
        // the mount, exactly like `docker cp`/bind-mount export does.
        val exportDir = Files.createTempDirectory("esurfing-export-")
        manifest.entries.filter { it.tier == "redacted" }.forEach { entry ->
            val dst = exportDir.resolve(entry.file)
            Files.createDirectories(dst.parent)
            Files.copy(runDir.resolve(entry.file), dst)
        }
        val exported = Files.walk(exportDir).toList().filter { Files.isRegularFile(it) }.map { exportDir.relativize(it).toString() }.toSet()
        assertTrue(exported.isNotEmpty(), "redacted-only export must contain files")
        assertFalse(
            exported.any { it.contains("sensitive") },
            "redacted-only export must not contain any sensitive/ path; got: $exported",
        )
        assertEquals(
            emptyList(),
            FixtureSecretScanner.scanDirectory(exportDir.toFile()),
            "exported (redacted-only) content must be canary-clean",
        )
    }

    // --- 5. normal-mode evidence output is canary-clean end to end ---

    @Test
    fun normalMode_twoRestarts_wholeMountDir_isCanaryClean() {
        runFullContainerCycle()
        runFullContainerCycle()
        assertEquals(
            emptyList(),
            FixtureSecretScanner.scanDirectory(mount.toFile()),
            "the persisted evidence tree must contain zero canary/real-key byte patterns",
        )
    }

    // --- 6. container packaging assets are canary/real-key clean ---

    @Test
    fun containerPackagingAssets_areCleanOfCanaryAndRealKeys() {
        val repoRoot = Path.of(System.getProperty("user.dir"))
        for (rel in listOf("Dockerfile", "docker-entrypoint.sh", "docker/README.md")) {
            val asset = repoRoot.resolve(rel).toFile()
            assertTrue(asset.isFile, "container asset must exist at repo root: $rel")
            assertEquals(
                emptyList(),
                FixtureSecretScanner.scanFile(asset),
                "container asset $rel must not embed canary secrets or real key material",
            )
        }
    }

    private data class StartedRun(val runId: String, val manifest: EvidenceManifest)
}