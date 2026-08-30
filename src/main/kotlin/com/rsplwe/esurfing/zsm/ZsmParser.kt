package com.rsplwe.esurfing.zsm

import com.rsplwe.esurfing.cipher.CipherFactory
import com.rsplwe.esurfing.cipher.CipherLookup
import com.rsplwe.esurfing.diagnostics.ProtocolErrorCode
import java.nio.charset.CharacterCodingException

/**
 * Deterministic, testable ZSM byte parser.
 *
 * Guarantees over the old inline parser in `Session.load`:
 *  - **Unsigned lengths**: keyLen/algoIdLen are read as `byte and 0xFF`, so a
 *    length byte >= 0x80 can never underflow `pos + len` (the old signed-byte
 *    reading could turn 0xFF into `-1` and crash on a negative slice).
 *  - **Explicit charset validation**: header and Algo-Id are strict UTF-8
 *    (`throwOnInvalidSequence = true`); malformed sequences are a typed
 *    failure, never replacement characters.
 *  - **Range checks**: every length is validated against the remaining payload
 *    before slicing.
 *  - **Trailing-byte record**: any bytes past the Algo-Id section are counted
 *    and carried on [ParsedZsm.trailingBytes] instead of being silently
 *    dropped.
 *  - **Unknown Algo-Id** is a typed [ZsmParseResult.Failure] resolved through
 *    the real [CipherFactory] dispatch table (never a duplicated id list).
 *
 * The parser never throws, never logs, and never touches the file system.
 */
@OptIn(ExperimentalStdlibApi::class)
object ZsmParser {

    private const val HEADER_BYTES = 3

    fun parse(zsm: ByteArray): ZsmParseResult {
        if (zsm.size < HEADER_BYTES + 1) {
            return malformed("zsm_too_short")
        }
        val header = try {
            zsm.decodeToString(0, HEADER_BYTES, throwOnInvalidSequence = true)
        } catch (e: CharacterCodingException) {
            return malformed("zsm_invalid_utf8")
        }

        var pos = HEADER_BYTES
        val keyLen = zsm[pos].toInt() and 0xFF // unsigned — no negative interpretation
        pos += 1
        if (pos + keyLen > zsm.size) {
            return malformed("zsm_key_len_overflow")
        }
        val rawKey = zsm.copyOfRange(pos, pos + keyLen)
        pos += keyLen

        if (pos >= zsm.size) {
            return malformed("zsm_missing_algo_id_len")
        }
        val algoIdLen = zsm[pos].toInt() and 0xFF
        pos += 1
        if (pos + algoIdLen > zsm.size) {
            return malformed("zsm_algo_id_len_overflow")
        }
        val algoIdBytes = zsm.copyOfRange(pos, pos + algoIdLen)
        val algoId = try {
            algoIdBytes.decodeToString(throwOnInvalidSequence = true)
        } catch (e: CharacterCodingException) {
            return malformed("zsm_invalid_utf8")
        }
        if (algoId.isEmpty()) {
            return malformed("zsm_empty_algo_id")
        }

        val parsed = ParsedZsm(
            header = header,
            rawKey = rawKey,
            algoId = algoId,
            keyLen = keyLen,
            algoIdLen = algoIdLen,
            trailingBytes = zsm.size - (pos + algoIdLen),
        )

        return when (CipherFactory.getInstance(algoId)) {
            is CipherLookup.Found -> ZsmParseResult.Success(parsed)
            is CipherLookup.Unknown -> ZsmParseResult.Failure(
                ProtocolErrorCode.UNKNOWN_ALGO_ID,
                "zsm_unknown_algo_id",
                parsed,
            )
        }
    }

    private fun malformed(summary: String): ZsmParseResult.Failure =
        ZsmParseResult.Failure(ProtocolErrorCode.ZSM_MALFORMED, summary)
}