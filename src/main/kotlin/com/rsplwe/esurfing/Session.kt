package com.rsplwe.esurfing

import com.rsplwe.esurfing.cipher.CipherFactory
import com.rsplwe.esurfing.cipher.CipherGuard
import com.rsplwe.esurfing.cipher.CipherInterface
import com.rsplwe.esurfing.cipher.CipherLookup
import com.rsplwe.esurfing.cipher.CipherOutcome
import com.rsplwe.esurfing.diagnostics.ProtocolErrorCode
import com.rsplwe.esurfing.diagnostics.ProtocolResult
import com.rsplwe.esurfing.zsm.ParsedZsm
import com.rsplwe.esurfing.zsm.ZsmEvidenceSink
import com.rsplwe.esurfing.zsm.ZsmParseResult
import com.rsplwe.esurfing.zsm.ZsmParser
import java.security.MessageDigest
import org.apache.log4j.Logger

object Session {

    private val logger: Logger = Logger.getLogger(Session::class.java)

    @Volatile
    private var initialized = false

    @Volatile
    private var cipher: CipherInterface? = null

    /** Set by Todo 5 tests / Todo 7 CLI wiring; null keeps the behaviour fully offline. */
    @Volatile
    private var evidenceSink: ZsmEvidenceSink? = null

    /** Protocol-layer typed outcome of the last [initialize]; null before the first attempt. */
    @Volatile
    private var lastProtocolResult: ProtocolResult? = null

    fun initialize(zsm: ByteArray) {
        logger.info("Initializing Session...")
        when (val result = ZsmParser.parse(zsm)) {
            is ZsmParseResult.Success -> initializeFrom(result.parsed, zsm)
            is ZsmParseResult.Failure -> reject(result.errorCode, result.safeSummary, result.parsed?.algoId, zsm)
        }
    }

    private fun initializeFrom(parsed: ParsedZsm, zsm: ByteArray) {
        when (val lookup = CipherFactory.getInstance(parsed.algoId)) {
            is CipherLookup.Found -> {
                cipher = lookup.cipher
                States.algoId = parsed.algoId
                initialized = true
                lastProtocolResult = ProtocolResult.Success
                // Non-secret summary only: lengths, header, Algo-Id and a one-way
                // fingerprint. The ZSM key bytes never reach a normal log.
                logger.info("ZSM header: ${parsed.header}")
                logger.info(
                    "ZSM key length: ${parsed.keyLen} bytes; algo id length: ${parsed.algoIdLen} bytes; trailing bytes: ${parsed.trailingBytes}",
                )
                logger.info("ZSM Algo Id: ${parsed.algoId}")
                logger.info("ZSM fingerprint: ${sha256Hex(parsed.rawKey)}")
            }

            // Unreachable: ZsmParser already resolved the same Algo-Id through the
            // same dispatch table. Kept as an exhaustive typed boundary instead of
            // a raw exception (sealed match, not defensive code).
            is CipherLookup.Unknown ->
                reject(ProtocolErrorCode.UNKNOWN_ALGO_ID, "zsm_unknown_algo_id", parsed.algoId, zsm)
        }
    }

    private fun reject(errorCode: ProtocolErrorCode, safeSummary: String, algoId: String?, zsm: ByteArray) {
        initialized = false
        cipher = null
        lastProtocolResult = ProtocolResult.Failure(errorCode, safeSummary)
        try {
            evidenceSink?.saveRawZsm(zsm, safeSummary)
        } catch (e: Exception) {
            // Evidence storage is best-effort forensics; a failing sink must not
            // mask the typed rejection itself.
            logger.error("ZSM evidence capture failed: ${e::class.java.simpleName}")
        }
        val suffix = algoId?.let { "; Algo Id: $it" } ?: ""
        logger.error("ZSM rejected: $safeSummary$suffix")
    }

    fun isInitialized(): Boolean {
        return initialized
    }

    /** Public API (kept): decrypt as plaintext; callers guard with [isInitialized]. */
    fun decrypt(hex: String): String {
        val current = cipher ?: throw IllegalStateException("Session not initialized")
        return current.decrypt(hex)
    }

    /** Public API (kept): encrypt as plaintext; callers guard with [isInitialized]. */
    fun encrypt(text: String): String {
        val current = cipher ?: throw IllegalStateException("Session not initialized")
        return current.encrypt(text)
    }

    /**
     * Typed decrypt for the diagnostics path: never throws, returns a
     * protocol-layer [CipherOutcome] (e.g. illegal block size, bad hex,
     * uninitialised session).
     */
    fun tryDecrypt(hex: String): CipherOutcome = CipherGuard.decrypt(cipher, hex)

    fun free() {
        initialized = false
        cipher = null
    }

    /** Protocol-layer typed result of the last [initialize] (Todo 6 wires diagnostics). */
    fun lastProtocolResult(): ProtocolResult? = lastProtocolResult

    /** Attach/detach the raw-ZSM evidence sink (null = offline, no files written). */
    fun attachZsmEvidenceSink(sink: ZsmEvidenceSink?) {
        evidenceSink = sink
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}