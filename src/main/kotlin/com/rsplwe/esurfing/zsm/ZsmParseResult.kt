package com.rsplwe.esurfing.zsm

import com.rsplwe.esurfing.diagnostics.ProtocolErrorCode

/**
 * Parsed, typed view of a ZSM payload. ZSM layout (fixed by the Todo 1
 * fixtures): `header(3) | keyLen(1) | key[keyLen] | algoIdLen(1) |
 * algoId[algoIdLen]`, with all lengths interpreted **unsigned**.
 *
 * A [Failure] is a protocol-layer typed failure (carries a [ProtocolErrorCode]),
 * never an exception — the caller decides how to surface it.
 */
sealed interface ZsmParseResult {
    val isSuccess: Boolean

    /** Structurally valid AND the Algo-Id resolves to a known algorithm. */
    data class Success(val parsed: ParsedZsm) : ZsmParseResult {
        override val isSuccess: Boolean get() = true
    }

    /**
     * Malformed ZSM or unrecognised Algo-Id. [parsed] is populated whenever the
     * failure happened after the structure was read (e.g. an unknown Algo-Id),
     * so safe fields (header/lengths/Algo-Id) stay available for evidence.
     */
    data class Failure(
        val errorCode: ProtocolErrorCode,
        val safeSummary: String,
        val parsed: ParsedZsm? = null,
    ) : ZsmParseResult {
        override val isSuccess: Boolean get() = false
    }
}

/** Non-secret-safe field container for a ZSM; [rawKey] must never be logged. */
data class ParsedZsm(
    val header: String,
    val rawKey: ByteArray,
    val algoId: String,
    val keyLen: Int,
    val algoIdLen: Int,
    val trailingBytes: Int,
)