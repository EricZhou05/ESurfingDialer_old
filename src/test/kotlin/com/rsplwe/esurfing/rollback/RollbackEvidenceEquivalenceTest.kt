package com.rsplwe.esurfing.rollback

import com.rsplwe.esurfing.DiagnosticsRunner
import com.rsplwe.esurfing.Options
import com.rsplwe.esurfing.ShutdownHandler
import com.rsplwe.esurfing.diagnostics.EvidenceManifest
import com.rsplwe.esurfing.fixture.AuthScenario
import com.rsplwe.esurfing.fixture.CanarySecrets
import com.rsplwe.esurfing.fixture.FixtureLoader
import com.rsplwe.esurfing.fixture.FixtureSecretScanner
import com.rsplwe.esurfing.fixture.TestAuthFlow
import com.rsplwe.esurfing.network.NetDiagnostics
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Rollback-safety equivalence for the CI / baseline story (Todo 9).
 *
 * The real rollback rehearsal runs two different versions (baseline and
 * diagnostic JARs) as containers against the SAME bind-mount directory, one
 * after the other, and then proves the ORIGINAL version can be re-deployed
 * ("rollback") without any old evidence being overwritten. This suite mirrors
 * that contract on the local file system with the same [DiagnosticsRunner]
 * machinery the container uses:
 *
 *  1. baseline cycle writes a run into [mount] — snapshot hash catalogue;
 *  2. diagnostic cycle writes its OWN run into the SAME [mount];
 *  3. rollback = a second baseline cycle runs again;
 *  4. the rollback must NOT overwrite/delete/modify the original baseline run
 *     (byte-for-byte identical host-side SHA-256 catalogue, directory still
 *     present), and the rolled-back run is a fresh, separately verifiable run.
 *
 * Version identity is modelled through a per-version fixture ticket value in
 * the auth exchange; every run still goes through the real authorization flow
 * against MockWebServer and a graceful shutdown (the SIGTERM equivalent) that
 * flushes EVENTS + manifest into [mount].
 *
 * All credentials are fictitious canaries from [CanarySecrets] — never a real
 * account. No file may be deleted by this suite.
 */
class RollbackEvidenceEquivalenceTest {

    /** Host-side directory that plays the role of `-v <dir>:/data/diagnostics`. */
    @TempDir
    lateinit var mount: Path

    private val zsm: ByteArray = FixtureLoader.bytes("zsm/valid.zsm")

    @BeforeEach
    fun setUp() {
        NetDiagnostics.resetForTest()
    }

    private fun options(smsCode: String = CanarySecrets.CANARY_SMS_DEMO): Options =
        Options(CanarySecrets.CANARY_USER_DEMO, CanarySecrets.CANARY_PASSWORD_DEMO, smsCode)

    private fun ticketXml(versionTag: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <response><result>success</result>
            <ticket>FIXTURE_TICKET_$versionTag</ticket>
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
     * One full "container run" for a given [versionTag] (baseline or
     * diagnostic): real authorization flow against MockWebServer, then a
     * graceful shutdown which flushes the events + manifest into [mount].
     * Each call resets the in-process diagnostics context first — exactly
     * like a fresh JVM / fresh container: new run context, same bind mount.
     */
    private fun runCycle(versionTag: String): StartedRun {
        NetDiagnostics.resetForTest()
        val session = TestAuthFlow.runSession(AuthScenario(options(), zsm, ticketXml(versionTag), loginXml()))
        val runner = DiagnosticsRunner.open(mount, mode = com.rsplwe.esurfing.diagnostics.RunMode.NORMAL)
        session.enqueueEncrypted(termOk())
        ShutdownHandler(session.client, runner).shutdown()
        session.close()
        return StartedRun(runner.runId, readManifest(mount.resolve(runner.runId)))
    }

    private fun readManifest(runDir: Path): EvidenceManifest =
        EvidenceManifest.fromJson(Files.readString(runDir.resolve("manifest.json")))

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Host-side, byte-for-byte catalogue of one run: relative path -> SHA-256.
     * Recomputing it from disk (outside the runner's memory) is exactly what
     * the docs' `sha256sum -c` over the manifest does.
     */
    private fun catalogue(runDir: Path): Map<String, String> =
        Files.walk(runDir).use { paths ->
            paths.filter { Files.isRegularFile(it) } // deterministic order, independent of iteration order
                .sorted()
                .map { runDir.relativize(it).toString() to sha256Hex(Files.readAllBytes(it)) }
                .toList()
                .toMap()
        }

    @Test
    fun rollback_baselineDiagnosticThenRollback_oldBaselineEvidenceHashIsUnchanged() {
        // Phase 1 — baseline version runs and persists its evidence.
        val baseline = runCycle("BASELINE")
        val baselineDir = mount.resolve(baseline.runId)
        val beforeRollback = catalogue(baselineDir)
        assertTrue(beforeRollback.isNotEmpty(), "baseline run must persist evidence files")
        assertTrue(
            baseline.manifest.entries.any { it.kind == "EVENT_JSONL" },
            "baseline run manifest must carry flushed EVENT_JSONL evidence",
        )

        // Phase 2 — diagnostic version runs against the SAME mount directory.
        val diagnostic = runCycle("DIAGNOSTIC")
        assertNotEquals(baseline.runId, diagnostic.runId, "diagnostic run gets a fresh runId")

        // Phase 3 — rollback: re-deploy the baseline version and run again.
        val rolledBack = runCycle("BASELINE")
        assertNotEquals(baseline.runId, rolledBack.runId, "rollback run gets a fresh runId")

        // ---- rollback must NOT overwrite / delete / modify old evidence ----
        assertEquals(
            beforeRollback,
            catalogue(baselineDir),
            "baseline evidence catalogue (rel-path -> sha256) must be byte-for-byte identical " +
                "after diagnostic + rollback runs",
        )

        // The rolled-back (baseline again) run is a fresh, verifiable evidence run.
        val rollbackCatalogue = catalogue(mount.resolve(rolledBack.runId))
        assertTrue(rollbackCatalogue.isNotEmpty(), "rollback run must persist its own evidence")
        assertTrue(
            rolledBack.manifest.entries.any { it.kind == "EVENT_JSONL" },
            "rollback (baseline re-deploy) run must flush EVENT_JSONL evidence too",
        )

        // The whole mount directory stays canary-clean after all three cycles.
        assertEquals(
            emptyList(),
            FixtureSecretScanner.scanDirectory(mount.toFile()),
            "persisted evidence tree after baseline/diagnostic/rollback must contain zero canary/real-key patterns",
        )
    }

    private data class StartedRun(val runId: String, val manifest: EvidenceManifest)
}