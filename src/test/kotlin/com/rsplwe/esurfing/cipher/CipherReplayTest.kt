package com.rsplwe.esurfing.cipher

import com.rsplwe.esurfing.diagnostics.ProtocolErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Replay contract for the nine production algorithms and the typed decrypt
 * boundary:
 *  - every known Algo-Id resolves through [CipherFactory] and round-trips
 *    encrypt → decrypt back to the original plaintext (no implementation,
 *    padding, key/IV or mapping is touched);
 *  - encryption is deterministic (fixed plaintext → fixed ciphertext), which
 *    is the project-protocol known vector;
 *  - unknown Algo-Ids are a typed, non-throwing [CipherLookup.Unknown];
 *  - malformed hex / illegal ciphertext length / bad padding / uninitialised
 *    session yield protocol-layer typed failures via [CipherGuard].
 */
class CipherReplayTest {

    private val knownIds = listOf(
        "CAFBCBAD-B6E7-4CAB-8A67-14D39F00CE1E",
        "A474B1C2-3DE0-4EA2-8C5F-7093409CE6C4",
        "5BFBA864-BBA9-42DB-8EAD-49B5F412BD81",
        "6E0B65FF-0B5B-459C-8FCE-EC7F2BEA9FF5",
        "B809531F-0007-4B5B-923B-4BD560398113",
        "F3974434-C0DD-4C20-9E87-DDB6814A1C48",
        "ED382482-F72C-4C41-A76D-28EEA0F1F2AF",
        "B3047D4E-67DF-4864-A6A5-DF9B9E525C79",
        "C32C68F9-CA81-4260-A329-BBAFD1A9CCD1",
    )

    /** Lengths cover unpadded/padded and exact-block paths across paddings. */
    private val plaintexts = listOf("Hi", "ESurfing-dialer", "0123456789ABCDEF", "0123456789ABCDEF01234567")

    private fun cipherFor(id: String): CipherInterface {
        val lookup = CipherFactory.getInstance(id)
        assertTrue(lookup is CipherLookup.Found, "known Algo-Id must resolve: $id")
        return (lookup as CipherLookup.Found).cipher
    }

    @Test
    fun nineAlgorithms_roundTrip_restoreOriginalPlaintext() {
        for (id in knownIds) {
            val cipher = cipherFor(id)
            for (plaintext in plaintexts) {
                val hex = cipher.encrypt(plaintext)
                assertEquals(
                    plaintext,
                    cipher.decrypt(hex),
                    "round-trip failed for $id with plaintext '$plaintext'",
                )
            }
        }
    }

    @Test
    fun nineAlgorithms_encrypt_isDeterministicKnownVector() {
        val payload = "ESurfing-dialer-replay-vector"
        for (id in knownIds) {
            val cipher = cipherFor(id)
            val first = cipher.encrypt(payload)
            val second = cipher.encrypt(payload)
            assertEquals(first, second, "encryption must be deterministic for $id")
            assertEquals(payload, cipher.decrypt(first), "decrypt of known vector must restore input for $id")
        }
    }

    @Test
    fun unknownAlgoId_lookup_isTyped_doesNotThrow() {
        val out = CipherFactory.getInstance("DEADBEEF-DEAD-BEEF-DEAD-DEADBEEFDEAD")
        assertTrue(out is CipherLookup.Unknown, "unknown id must be a typed Unknown, got: $out")
        assertEquals("DEADBEEF-DEAD-BEEF-DEAD-DEADBEEFDEAD", (out as CipherLookup.Unknown).algoId)
    }

    @Test
    fun invalidHexChars_decrypt_yieldsTypedFailure() {
        val cipher = cipherFor(knownIds.first())
        val out = CipherGuard.decrypt(cipher, "zz00zz")
        assertTypedDecryptFailure(out)
    }

    @Test
    fun oddLengthHex_decrypt_yieldsTypedFailure() {
        val cipher = cipherFor(knownIds.first())
        val out = CipherGuard.decrypt(cipher, "ABC")
        assertTypedDecryptFailure(out)
    }

    @Test
    fun illegalBlockSize_ecbDecrypt_yieldsTypedFailure() {
        val cipher = cipherFor("A474B1C2-3DE0-4EA2-8C5F-7093409CE6C4") // AES/ECB/NoPadding
        val out = CipherGuard.decrypt(cipher, "1122") // 2 bytes, not a multiple of 16
        assertTrue(out is CipherOutcome.Failure, "illegal block size must be a typed failure")
        assertEquals("decrypt_illegal_block_size", (out as CipherOutcome.Failure).safeSummary)
    }

    @Test
    fun truncatedIv_cbcDecrypt_yieldsTypedFailure() {
        val cipher = cipherFor("CAFBCBAD-B6E7-4CAB-8A67-14D39F00CE1E") // AES/CBC with embedded IV
        val out = CipherGuard.decrypt(cipher, "112233") // shorter than the 16-byte IV
        assertTypedDecryptFailure(out)
    }

    @Test
    fun nonBlockMultiple_sm4CbcDecrypt_yieldsTypedFailure() {
        val cipher = cipherFor("F3974434-C0DD-4C20-9E87-DDB6814A1C48") // SM4/CBC
        val out = CipherGuard.decrypt(cipher, "112233") // not a multiple of the 16-byte block
        assertTypedDecryptFailure(out)
    }

    @Test
    fun uninitializedSession_decrypt_yieldsTypedFailure() {
        val out = CipherGuard.decrypt(null, "0011")
        assertTrue(out is CipherOutcome.Failure)
        assertEquals("decrypt_uninitialized", (out as CipherOutcome.Failure).safeSummary)
    }

    private fun assertTypedDecryptFailure(out: CipherOutcome) {
        assertTrue(out is CipherOutcome.Failure, "malformed decryption must be a typed failure, got: $out")
        assertEquals(ProtocolErrorCode.DECRYPT, (out as CipherOutcome.Failure).errorCode)
    }
}