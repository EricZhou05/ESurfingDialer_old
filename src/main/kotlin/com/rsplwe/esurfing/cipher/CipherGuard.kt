package com.rsplwe.esurfing.cipher

import com.rsplwe.esurfing.diagnostics.ProtocolErrorCode
import javax.crypto.BadPaddingException
import javax.crypto.IllegalBlockSizeException

/**
 * Typed outcome of a replay/Boundary decrypt.
 *
 * The implementations themselves are untouched; this guard only converts
 * their failure modes (illegal hex, illegal ciphertext length, bad padding,
 * uninitialised session) into a [CipherOutcome.Failure] at the protocol layer
 * instead of a raw exception crossing the boundary.
 */
sealed interface CipherOutcome {
    val isSuccess: Boolean

    data class Success(val plain: String) : CipherOutcome {
        override val isSuccess: Boolean get() = true
    }

    data class Failure(
        val errorCode: ProtocolErrorCode,
        val safeSummary: String,
    ) : CipherOutcome {
        override val isSuccess: Boolean get() = false
    }
}

/**
 * Boundary guard translating decrypt failures into protocol-layer typed
 * failures. Never throws for the classified inputs:
 *  - `null` cipher (session not initialized) → `decrypt_uninitialized`
 *  - illegal hex (`hexToByteArray`) / SM4/ModXTEA length `require` →
 *    `decrypt_bad_input`
 *  - ciphertext shorter than an embedded IV (AES/DES-CBC) →
 *    `decrypt_truncated_ciphertext`
 *  - block-size violations (AES-ECB/DES-ECB `doFinal`) →
 *    `decrypt_illegal_block_size`
 *  - PKCS#7 padding violations (SM4) → `decrypt_bad_padding`
 */
object CipherGuard {

    fun decrypt(cipher: CipherInterface?, hex: String): CipherOutcome {
        return try {
            if (cipher == null) {
                CipherOutcome.Failure(ProtocolErrorCode.DECRYPT, "decrypt_uninitialized")
            } else {
                CipherOutcome.Success(cipher.decrypt(hex))
            }
        } catch (e: Exception) {
            CipherOutcome.Failure(ProtocolErrorCode.DECRYPT, classify(e))
        }
    }

    private fun classify(e: Exception): String = when (e) {
        is BadPaddingException -> "decrypt_bad_padding"
        is IllegalBlockSizeException -> "decrypt_illegal_block_size"
        is IllegalArgumentException -> "decrypt_bad_input"
        is IndexOutOfBoundsException -> "decrypt_truncated_ciphertext"
        is IllegalStateException -> "decrypt_uninitialized"
        else -> "decrypt_failed"
    }
}