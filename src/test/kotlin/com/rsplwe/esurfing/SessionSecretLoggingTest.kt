package com.rsplwe.esurfing

import com.rsplwe.esurfing.fixture.FixtureLoader
import com.rsplwe.esurfing.fixture.TestLogAppender
import org.apache.log4j.Logger
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the "ZSM key logged to console" defect fixed by Todo 5.
 *
 * Session.load used to log `logger.info("Key: $key")` (old Session.kt:54). The
 * key inside a ZSM is a session secret and must never appear in ordinary logs.
 * Re-enabled in Todo 5; must stay green.
 */
class SessionSecretLoggingTest {

    @Test
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
