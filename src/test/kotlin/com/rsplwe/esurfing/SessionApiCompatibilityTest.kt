package com.rsplwe.esurfing

import com.rsplwe.esurfing.cipher.CipherOutcome
import com.rsplwe.esurfing.diagnostics.ProtocolErrorCode
import com.rsplwe.esurfing.diagnostics.ProtocolResult
import com.rsplwe.esurfing.fixture.FixtureLoader
import org.junit.jupiter.api.AfterEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Public-API compatibility of [Session] (Client.kt and DialerApp.kt depend on
 * these exact signatures): `initialize(zsm)`, `isInitialized()`, `encrypt`,
 * `decrypt`, `free`. Unknown/malformed ZSM must leave `isInitialized() ==
 * false` WITHOUT throwing — the failure is a protocol-layer typed result, and
 * raw decryption of an uninitialised session is also a typed failure.
 */
class SessionApiCompatibilityTest {

    @AfterEach
    fun reset() {
        Session.free()
        Session.attachZsmEvidenceSink(null)
    }

    @Test
    fun initialize_validZsm_roundTripsThroughSession() {
        Session.initialize(FixtureLoader.bytes("zsm/valid.zsm"))
        assertTrue(Session.isInitialized())
        val text = "api-round-trip-payload-16chars!"
        val hex = Session.encrypt(text)
        assertEquals(text, Session.decrypt(hex), "Session.encrypt/decrypt must round-trip")
    }

    @Test
    fun initialize_validZsm_lastProtocolResult_isSuccess() {
        Session.initialize(FixtureLoader.bytes("zsm/valid.zsm"))
        assertTrue(Session.isInitialized())
        assertTrue(Session.lastProtocolResult() is ProtocolResult.Success)
    }

    @Test
    fun initialize_unknownAlgoId_isFalse_andNeverThrows() {
        SessionAttacker.assertNoThrow {
            Session.initialize(FixtureLoader.bytes("zsm/malformed-unknown-algo.zsm"))
        }
        assertFalse(Session.isInitialized())
        val last = Session.lastProtocolResult()
        assertTrue(last is ProtocolResult.Failure)
        assertEquals(ProtocolErrorCode.UNKNOWN_ALGO_ID, (last as ProtocolResult.Failure).errorCode)
    }

    @Test
    fun initialize_truncatedZsm_isFalse_andNeverThrows() {
        SessionAttacker.assertNoThrow { Session.initialize(FixtureLoader.bytes("zsm/malformed-truncated.zsm")) }
        assertFalse(Session.isInitialized())
    }

    @Test
    fun initialize_keyLenOverflow_isFalse_andNeverThrows() {
        SessionAttacker.assertNoThrow { Session.initialize(FixtureLoader.bytes("zsm/malformed-keylen-overflow.zsm")) }
        assertFalse(Session.isInitialized())
    }

    @Test
    fun free_resetsInitializedFlag() {
        Session.initialize(FixtureLoader.bytes("zsm/valid.zsm"))
        assertTrue(Session.isInitialized())
        Session.free()
        assertFalse(Session.isInitialized(), "free() must reset the initialized flag")
    }

    @Test
    fun tryDecryptOnFreedSession_isTypedFailure_notThrow() {
        Session.free()
        val out = Session.tryDecrypt("00")
        assertTrue(out is CipherOutcome.Failure, "decrypt on a freed session must be a typed failure")
        assertEquals(ProtocolErrorCode.DECRYPT, (out as CipherOutcome.Failure).errorCode)
    }

    private object SessionAttacker {
        fun assertNoThrow(block: () -> Unit) {
            try {
                block()
            } catch (e: Throwable) {
                fail("must not throw, got ${e::class.java.simpleName}: ${e.message}", e)
            }
        }
    }
}