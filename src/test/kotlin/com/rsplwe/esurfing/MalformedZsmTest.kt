package com.rsplwe.esurfing

import com.rsplwe.esurfing.fixture.FixtureLoader
import com.rsplwe.esurfing.fixture.TestLogAppender
import org.apache.log4j.Logger
import org.junit.jupiter.api.Disabled
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RED (TDD) — exposes malformed-ZSM handling boundaries (Session.load,
 * Session.kt:23-58).
 *
 * Already-correct boundaries (must stay green):
 *  - payload shorter than 4 bytes is rejected without crashing
 *  - keyLen/algoIdLen overflow is rejected without crashing
 *
 * Current defects (must go red until Todo 5):
 *  - unknown Algo-ID falls into the catch-all and writes the raw ZSM to
 *    `algo_dump_*.bin` in the current working directory (raw secret leak +
 *    cwd side effect) and logs the dump path to ordinary logs.
 */
class MalformedZsmTest {

    private fun algoDumpFiles(): List<String> =
        File(".").listFiles()?.mapNotNull { f ->
            if (f.name.startsWith("algo_dump_") && f.isFile) f.name else null
        } ?: emptyList()

    @Test
    fun truncatedZsm_isRejectedWithoutCrash() {
        val zsm = FixtureLoader.bytes("zsm/malformed-truncated.zsm") // "AA" = 2 bytes
        assertTrue(zsm.size < 4)
        Session.initialize(zsm)
        assertFalse(Session.isInitialized(), "payload < 4 bytes must not initialize")
    }

    @Test
    fun emptyZsm_isRejectedWithoutCrash() {
        Session.initialize(ByteArray(0))
        assertFalse(Session.isInitialized(), "empty payload must not initialize")
    }

    @Test
    fun keyLenOverflow_isRejectedWithoutCrash() {
        val zsm = FixtureLoader.bytes("zsm/malformed-keylen-overflow.zsm")
        Session.initialize(zsm)
        assertFalse(Session.isInitialized(), "keyLen overflow must not initialize")
    }

    @Test
    @Disabled("TDD red-phase: expected failure until Todo 5 stops dumping raw ZSM to cwd")
    fun unknownAlgoId_mustNotWriteDumpFileToCwd() {
        val before = algoDumpFiles()
        Session.initialize(FixtureLoader.bytes("zsm/malformed-unknown-algo.zsm"))
        val after = algoDumpFiles()
        assertFalse(
            after.size > before.size,
            "unknown Algo-ID must not write algo_dump_*.bin into the working directory. Created: ${after - before}"
        )
    }

    @Test
    @Disabled("TDD red-phase: expected failure until Todo 5 stops advertising the dump path")
    fun unknownAlgoId_mustNotLogDumpPath() {
        val appender = TestLogAppender()
        Logger.getRootLogger().addAppender(appender)
        try {
            Session.initialize(FixtureLoader.bytes("zsm/malformed-unknown-algo.zsm"))
            val leaked = appender.messages.any { it.contains("algo_dump") }
            assertFalse(
                leaked,
                "ordinary logs must not advertise the algo_dump file path, but found: " +
                    appender.messages.filter { it.contains("algo_dump") }
            )
        } finally {
            Session.free()
            Logger.getRootLogger().removeAppender(appender)
        }
    }
}
