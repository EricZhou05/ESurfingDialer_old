package com.rsplwe.esurfing.zsm

import com.rsplwe.esurfing.diagnostics.ProtocolErrorCode
import com.rsplwe.esurfing.fixture.FixtureLoader
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Boundary contract of the standalone [ZsmParser]: unsigned lengths, strict
 * UTF-8, range checks, trailing-byte record, typed failures (never an
 * exception) for truncated/overflow/unknown inputs, and all nine known
 * Algo-Ids parse successfully.
 */
class ZsmParserTest {

    private val aesCbcAlgoId = "CAFBCBAD-B6E7-4CAB-8A67-14D39F00CE1E"

    private fun zsm(
        header: ByteArray = "AAA".toByteArray(),
        key: ByteArray = "CAnary-K3y-AB12X".toByteArray(),
        algoId: String = aesCbcAlgoId,
        keyLenByte: Int = key.size,
        algoIdLenByte: Int = algoId.length,
    ): ByteArray =
        header + byteArrayOf((keyLenByte and 0xFF).toByte()) + key +
            byteArrayOf((algoIdLenByte and 0xFF).toByte()) + algoId.toByteArray()

    private fun asFailure(result: ZsmParseResult): ZsmParseResult.Failure {
        assertTrue(result is ZsmParseResult.Failure, "expected typed failure, got: $result")
        return result as ZsmParseResult.Failure
    }

    private fun asSuccess(result: ZsmParseResult): ZsmParseResult.Success {
        assertTrue(result is ZsmParseResult.Success, "expected success, got: $result")
        return result as ZsmParseResult.Success
    }

    // --- truncated / empty -------------------------------------------------

    @Test
    fun parse_emptyBytes_isTypedFailure() {
        val failure = asFailure(ZsmParser.parse(ByteArray(0)))
        assertEquals(ProtocolErrorCode.ZSM_MALFORMED, failure.errorCode)
        assertEquals("zsm_too_short", failure.safeSummary)
        assertNull(failure.parsed)
    }

    @Test
    fun parse_truncatedHeader_isTypedFailure() {
        val failure = asFailure(ZsmParser.parse(byteArrayOf(0x41, 0x41)))
        assertEquals(ProtocolErrorCode.ZSM_MALFORMED, failure.errorCode)
        assertEquals("zsm_too_short", failure.safeSummary)
    }

    @Test
    fun parse_missingAlgoIdLenByte_isTypedFailure() {
        val bytes = "AAA".toByteArray() + byteArrayOf(16) + "CAnary-K3y-AB12X".toByteArray()
        val failure = asFailure(ZsmParser.parse(bytes))
        assertEquals("zsm_missing_algo_id_len", failure.safeSummary)
    }

    // --- keyLen: zero / overflow / unsigned interpretation -----------------

    @Test
    fun parse_zeroKeyLen_isAccepted() {
        val success = asSuccess(ZsmParser.parse(zsm(key = ByteArray(0), keyLenByte = 0)))
        assertEquals(0, success.parsed.keyLen)
        assertTrue(success.parsed.rawKey.isEmpty())
    }

    @Test
    fun parse_keyLenOverflow_isTypedFailure() {
        // header(3) + keyLen(=5, 1 byte) + only 2 key bytes -> 4+5=9 > 6 -> overflow
        val bytes = "AAA".toByteArray() + byteArrayOf(5) + "KE".toByteArray()
        val failure = asFailure(ZsmParser.parse(bytes))
        assertEquals("zsm_key_len_overflow", failure.safeSummary)
    }

    @Test
    fun parse_keyLenByteAbove127_isUnsignedAndTypedFailure_notCrash() {
        // Old code read keyLen as a signed Byte: 0xFF became -1 and the
        // bounds check was bypassed (crash on a negative slice). Unsigned
        // reading turns 0xFF into 255 -> overflow -> typed failure.
        val bytes = "AAA".toByteArray() + byteArrayOf(0xFF.toByte()) + "KE".toByteArray() +
            byteArrayOf(aesCbcAlgoId.length.toByte()) + aesCbcAlgoId.toByteArray()
        val failure = asFailure(ZsmParser.parse(bytes))
        assertEquals("zsm_key_len_overflow", failure.safeSummary)
    }

    @Test
    fun parse_keyLenOverflowFixture_isTypedFailure() {
        val failure = asFailure(ZsmParser.parse(FixtureLoader.bytes("zsm/malformed-keylen-overflow.zsm")))
        assertEquals("zsm_key_len_overflow", failure.safeSummary)
    }

    // --- algoIdLen: zero / overflow ----------------------------------------

    @Test
    fun parse_algoIdLenOverflow_isTypedFailure() {
        val bytes = zsm(algoIdLenByte = 20, algoId = "ABC".repeat(1))
        val failure = asFailure(ZsmParser.parse(bytes))
        assertEquals("zsm_algo_id_len_overflow", failure.safeSummary)
    }

    @Test
    fun parse_zeroAlgoIdLen_isTypedFailure() {
        val bytes = zsm(algoId = "", algoIdLenByte = 0)
        val failure = asFailure(ZsmParser.parse(bytes))
        assertEquals("zsm_empty_algo_id", failure.safeSummary)
    }

    // --- charset --------------------------------------------------------------

    @Test
    fun parse_invalidUtf8InHeader_isTypedFailure() {
        val bytes = byteArrayOf(0xFF.toByte(), 0x41, 0x42) +
            byteArrayOf(16) + "CAnary-K3y-AB12X".toByteArray() +
            byteArrayOf(aesCbcAlgoId.length.toByte()) + aesCbcAlgoId.toByteArray()
        val failure = asFailure(ZsmParser.parse(bytes))
        assertEquals("zsm_invalid_utf8", failure.safeSummary)
    }

    @Test
    fun parse_invalidUtf8InAlgoId_isTypedFailure() {
        val bytes = "AAA".toByteArray() + byteArrayOf(2) + "KE".toByteArray() +
            byteArrayOf(1) + byteArrayOf(0xFF.toByte())
        val failure = asFailure(ZsmParser.parse(bytes))
        assertEquals("zsm_invalid_utf8", failure.safeSummary)
    }

    // --- success paths --------------------------------------------------------

    @Test
    fun parse_validFixture_isSuccess_withExactFields() {
        val success = asSuccess(ZsmParser.parse(FixtureLoader.bytes("zsm/valid.zsm")))
        val parsed = success.parsed
        assertEquals("AAA", parsed.header)
        assertEquals(16, parsed.keyLen)
        assertContentEquals("CAnary-K3y-AB12X".toByteArray(), parsed.rawKey)
        assertEquals(aesCbcAlgoId, parsed.algoId)
        assertEquals(36, parsed.algoIdLen)
        assertEquals(0, parsed.trailingBytes)
        assertTrue(success.isSuccess)
        assertFalse(success.parsed.rawKey.isEmpty())
    }

    @Test
    fun parse_trailingBytes_areRecorded() {
        val bytes = FixtureLoader.bytes("zsm/valid.zsm") + byteArrayOf(0x01, 0x02, 0x03)
        val success = asSuccess(ZsmParser.parse(bytes))
        assertEquals(3, success.parsed.trailingBytes)
    }

    @Test
    fun parse_allNineKnownAlgoIds_areSuccess() {
        val knownIds = listOf(
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
        for (id in knownIds) {
            val success = asSuccess(ZsmParser.parse(zsm(algoId = id)))
            assertEquals(id, success.parsed.algoId, "expected known id to parse: $id")
        }
    }

    // --- unknown Algo-Id ------------------------------------------------------

    @Test
    fun parse_unknownAlgoId_isTypedFailure_withParsedFields() {
        val failure = asFailure(ZsmParser.parse(FixtureLoader.bytes("zsm/malformed-unknown-algo.zsm")))
        assertEquals(ProtocolErrorCode.UNKNOWN_ALGO_ID, failure.errorCode)
        assertEquals("zsm_unknown_algo_id", failure.safeSummary)
        val parsed = failure.parsed
        assertNotNull(parsed, "unknown-algo failure must carry the parsed structure")
        assertEquals("AAA", parsed.header)
        assertEquals("DEADBEEF-DEAD-BEEF-DEAD-DEADBEEFDEAD", parsed.algoId)
        assertEquals(16, parsed.keyLen)
    }
}