package com.rsplwe.esurfing

import com.rsplwe.esurfing.fixture.FixtureLoader
import com.rsplwe.esurfing.fixture.TestLogAppender
import org.apache.log4j.Logger
import org.junit.jupiter.api.Disabled
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RED (TDD) — exposes the "ZSM key logged to console" defect.
 *
 * Session.load (Session.kt:54) logs `logger.info("Key: $key")`. The key inside
 * a ZSM is a session secret and must never appear in ordinary logs.
 *
 * Expected to FAIL until Todo 5 removes the key from the normal log path.
 */
class SessionSecretLoggingTest {

    @Test
    @Disabled("TDD red-phase: expected failure until Todo 5 removes the ZSM key from logs")
    fun sessionLoad_mustNotLogZsmKey() {
        val zsm = FixtureLoader.bytes("zsm/valid.zsm")
        val key = "CAnary-K3y-AB12X" // the fictitious key embedded in valid.zsm

        val appender = TestLogAppender()
        Logger.getRootLogger().addAppender(appender)
        try {
            Session.initialize(zsm)
            assertTrue(Session.isInitialized(), "valid fixture ZSM must initialize")

            val leaked = appender.messages.any { it.contains(key) }
            assertFalse(
                leaked,
                "Session log must not contain the ZSM key, but found it in: ${appender.messages.filter { it.contains(key) }}"
        )
        } finally {
            Session.free()
            Logger.getRootLogger().removeAppender(appender)
        }
    }
}
