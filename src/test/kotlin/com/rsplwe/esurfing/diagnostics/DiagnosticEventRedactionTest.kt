package com.rsplwe.esurfing.diagnostics

import com.rsplwe.esurfing.fixture.CanarySecrets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Secret-leak contract: no canary secret may ever appear in serialized output.
 * Free-text summaries are redacted against caller-supplied sensitive terms and
 * built-in secret-shape patterns before being written to JSONL.
 */
class DiagnosticEventRedactionTest {

    @Test
    fun canarySecrets_neverAppearInEncodedOutput() {
        for (canary in CanarySecrets.all) {
            val ctx = DiagnosticContext.start()
            val leakyEvent = ctx.event(
                stage = DiagnosticStage.LOGIN,
                result = BusinessResult.Failure(BusinessErrorCode.REJECTED, "login rejected, secret=$canary"),
                timestampEpochMillis = 1L,
            )
            val line = DiagnosticEventJson.encode(leakyEvent, sensitiveTerms = CanarySecrets.all)
            assertFalse(
                line.contains(canary),
                "encoded line must not contain canary '$canary', got: $line"
            )
            assertTrue(line.contains(DiagnosticRedactor.REDACTED), "leaked secret must be replaced with redaction marker")
        }
    }

    @Test
    fun canaryByteKeyHex_neverAppearsInEncodedOutput() {
        val keyHex = CanarySecrets.CANARY_BYTE_KEY.joinToString("") { "%02x".format(it) }
        val ctx = DiagnosticContext.start()
        val leakyEvent = ctx.event(
            stage = DiagnosticStage.SESSION_ZSM,
            result = ProtocolResult.Failure(ProtocolErrorCode.UNKNOWN_ALGO_ID, "zsm key=$keyHex"),
            timestampEpochMillis = 1L,
        )
        val line = DiagnosticEventJson.encode(leakyEvent, sensitiveTerms = listOf(keyHex))
        assertFalse(line.contains(keyHex), "canary byte key hex must be redacted from output")
    }

    @Test
    fun defaultPatterns_redactPasswdAndTicketShapes() {
        val ctx = DiagnosticContext.start()
        val event = ctx.event(
            stage = DiagnosticStage.LOGIN,
            result = BusinessResult.Failure(BusinessErrorCode.REJECTED, "passwd=hunter2 rejected"),
            timestampEpochMillis = 1L,
        )
        val line = DiagnosticEventJson.encode(event) // no explicit sensitive terms
        assertFalse(line.contains("hunter2"), "default patterns must redact passwd= value, got: $line")
        assertTrue(line.contains(DiagnosticRedactor.REDACTED))
    }

    @Test
    fun cleanSummary_passesThroughUnchanged() {
        val ctx = DiagnosticContext.start()
        val event = ctx.event(
            stage = DiagnosticStage.HEARTBEAT,
            result = HttpResult.Failure(HttpErrorCode.NON_2XX, "http 503"),
            timestampEpochMillis = 1L,
        )
        val line = DiagnosticEventJson.encode(event)
        assertTrue(line.contains("http 503"), "clean summaries must survive encoding: $line")
        assertFalse(line.contains(DiagnosticRedactor.REDACTED), "clean summary must not be falsely redacted")
    }

    @Test
    fun redactor_replacesExplicitSensitiveTerms() {
        val term = CanarySecrets.CANARY_PASSWORD_DEMO
        assertEquals(
            "secret is [REDACTED]",
            DiagnosticRedactor.redact("secret is $term", sensitiveTerms = listOf(term))
        )
    }
}
