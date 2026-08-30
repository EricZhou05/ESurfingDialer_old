package com.rsplwe.esurfing

import com.rsplwe.esurfing.diagnostics.EvidenceManifest
import com.rsplwe.esurfing.diagnostics.ProtocolErrorCode
import com.rsplwe.esurfing.diagnostics.ProtocolResult
import com.rsplwe.esurfing.diagnostics.RunMode
import com.rsplwe.esurfing.fixture.FixtureLoader
import com.rsplwe.esurfing.fixture.TestLogAppender
import com.rsplwe.esurfing.zsm.ZsmEvidenceSink
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.apache.log4j.Logger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Raw-ZSM forensics boundary: the original bytes reach disk ONLY through the
 * EvidenceStore in explicit SENSITIVE mode (with a reproducible raw hash); in
 * NORMAL mode the write is refused and no sensitive content exists. The old
 * `algo_dump_*.bin` current-directory write is gone.
 */
class SessionZsmEvidenceTest {

    @TempDir
    lateinit var tempDir: Path

    @AfterEach
    fun reset() {
        Session.free()
        Session.attachZsmEvidenceSink(null)
    }

    @Test
    fun unknownAlgoId_sensitiveMode_savesRawZsmAndManifestHash() {
        val sink = ZsmEvidenceSink.open(tempDir, mode = RunMode.SENSITIVE)
        Session.attachZsmEvidenceSink(sink)
        val zsm = FixtureLoader.bytes("zsm/malformed-unknown-algo.zsm")

        Session.initialize(zsm)
        assertFalse(Session.isInitialized(), "unknown Algo-Id must not initialize")

        val last = Session.lastProtocolResult()
        assertTrue(last is ProtocolResult.Failure, "typed protocol failure must be observable")
        assertEquals(ProtocolErrorCode.UNKNOWN_ALGO_ID, (last as ProtocolResult.Failure).errorCode)

        val runDir = tempDir.resolve(sink.runId)
        assertTrue(Files.isDirectory(runDir.resolve("sensitive")), "sensitive/ must exist under SENSITIVE mode")

        val manifest = EvidenceManifest.fromJson(Files.readString(runDir.resolve("manifest.json")))
        val rawEntry = manifest.entries.single { it.tier == "sensitive" && it.kind == "ZSM_RAW" }
        val rawFile = runDir.resolve(rawEntry.file)
        assertContentEquals(zsm, Files.readAllBytes(rawFile), "sensitive raw must be the original ZSM")
        assertEquals(sha256Hex(rawFile), rawEntry.sha256, "raw hash must be reproducible from the file")

        val redactedEntry = manifest.entries.single { it.tier == "redacted" && it.kind == "ZSM_RAW" }
        assertTrue(Files.isRegularFile(runDir.resolve(redactedEntry.file)), "an independent redacted derivative must exist")
        assertEquals(sha256Hex(runDir.resolve(redactedEntry.file)), redactedEntry.sha256)
    }

    @Test
    fun unknownAlgoId_normalMode_writesNoSensitiveContent() {
        val sink = ZsmEvidenceSink.open(tempDir, mode = RunMode.NORMAL)
        Session.attachZsmEvidenceSink(sink)

        Session.initialize(FixtureLoader.bytes("zsm/malformed-unknown-algo.zsm"))
        assertFalse(Session.isInitialized())

        val runDir = tempDir.resolve(sink.runId)
        assertFalse(Files.exists(runDir.resolve("sensitive")), "NORMAL mode must not create sensitive/")
    }

    @Test
    fun unknownAlgoId_logs_doNotContainZsmKeyOrDumpPath() {
        val appender = TestLogAppender()
        Logger.getRootLogger().addAppender(appender)
        try {
            Session.initialize(FixtureLoader.bytes("zsm/malformed-unknown-algo.zsm"))
            val leakedKey = appender.messages.any { it.contains("CAnary-K3y-CD34Y") }
            val leakedPath = appender.messages.any { it.contains("algo_dump") }
            assertFalse(leakedKey, "logs must not contain the ZSM key, found: ${appender.messages.filter { it.contains("CAnary-K3y-CD34Y") }}")
            assertFalse(leakedPath, "logs must not advertise a dump path")
        } finally {
            Session.free()
            Logger.getRootLogger().removeAppender(appender)
        }
    }

    private fun sha256Hex(file: Path): String =
        MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(file))
            .joinToString("") { "%02x".format(it) }
}