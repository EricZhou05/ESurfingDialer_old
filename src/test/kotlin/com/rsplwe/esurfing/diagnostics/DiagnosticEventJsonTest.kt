package com.rsplwe.esurfing.diagnostics

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Stable JSON Lines encoding contract: one event per line, each line a fully
 * self-contained JSON object that round-trips through [DiagnosticEventJson],
 * serialized through an explicit field whitelist with no reflection.
 */
class DiagnosticEventJsonTest {

    @Test
    fun encodeLines_thenDecodeLines_roundTripsAllFields() {
        val ctx = DiagnosticContext.start()
        val events = listOf(
            ctx.event(
                stage = DiagnosticStage.PORTAL,
                result = TransportResult.Success,
                timestampEpochMillis = 1000L,
                durationMs = 5L,
                truncated = false,
                evidenceRef = null,
            ),
            ctx.event(
                stage = DiagnosticStage.TICKET,
                result = ProtocolResult.Failure(ProtocolErrorCode.ZSM_MALFORMED, "zsm header invalid"),
                timestampEpochMillis = 2000L,
                durationMs = 12L,
                truncated = true,
                evidenceRef = "evidence/run-1/sensitive/0001.bin",
            ),
            ctx.event(
                stage = DiagnosticStage.LOGIN,
                result = BusinessResult.Failure(BusinessErrorCode.REJECTED, "login rejected"),
                timestampEpochMillis = 3000L,
                durationMs = null,
                truncated = false,
                evidenceRef = null,
            ),
        )

        val jsonl = DiagnosticEventJson.encodeLines(events)
        val lines = jsonl.split("\n")
        assertEquals(3, lines.size, "one event per line")
        assertEquals(events, DiagnosticEventJson.decodeLines(jsonl), "full batch round-trips to equal events")
    }

    @Test
    fun eachLine_isIndependentlyParsable() {
        val ctx = DiagnosticContext.start()
        val event = ctx.event(
            stage = DiagnosticStage.HEARTBEAT,
            result = HttpResult.Failure(HttpErrorCode.NON_2XX, "http 503"),
            timestampEpochMillis = 42L,
            durationMs = 7L,
            truncated = false,
            evidenceRef = null,
        )
        val line = DiagnosticEventJson.encode(event)
        // decode this single line in isolation — must equal the original event
        assertEquals(event, DiagnosticEventJson.decode(line))
    }

    @Test
    fun encodedLine_containsExactlyTheWhitelistedKeys() {
        val ctx = DiagnosticContext.start()
        val event = ctx.event(DiagnosticStage.LOGIN, BusinessResult.Success, timestampEpochMillis = 1L)
        val line = DiagnosticEventJson.encode(event)

        val keys = JsonParser.parseString(line).asJsonObject.keySet()
        assertEquals(DiagnosticEventJson.WHITELIST, keys, "serialized object must contain exactly the whitelisted fields")
    }

    @Test
    fun whitelist_excludesSecretBearingFields() {
        val forbidden = setOf(
            "exception", "stackTrace", "requestBody", "request", "response",
            "password", "passwd", "sms", "ticket", "cookie", "key", "iv", "url",
        )
        assertTrue(
            forbidden.none { it in DiagnosticEventJson.WHITELIST },
            "whitelist must never include secret-bearing fields: $forbidden"
        )
    }

    @Test
    fun decode_rejectsUnknownFields() {
        val ctx = DiagnosticContext.start()
        val line = DiagnosticEventJson.encode(ctx.event(DiagnosticStage.LOGIN, BusinessResult.Success, timestampEpochMillis = 1L))
        val tampered = JsonParser.parseString(line).asJsonObject.apply { addProperty("password", "hunter2") }
        assertFailsWith<IllegalArgumentException> { DiagnosticEventJson.decode(tampered.toString()) }
    }

    @Test
    fun decode_rejectsMissingRequiredFields() {
        val ctx = DiagnosticContext.start()
        val line = DiagnosticEventJson.encode(ctx.event(DiagnosticStage.LOGIN, BusinessResult.Success, timestampEpochMillis = 1L))
        val obj = JsonParser.parseString(line).asJsonObject
        obj.remove("stage")
        assertFailsWith<IllegalArgumentException> { DiagnosticEventJson.decode(obj.toString()) }
    }

    @Test
    fun decode_rejectsMalformedJsonAndUnknownStage() {
        val ctx = DiagnosticContext.start()
        val line = DiagnosticEventJson.encode(ctx.event(DiagnosticStage.LOGIN, BusinessResult.Success, timestampEpochMillis = 1L))
        assertFailsWith<IllegalArgumentException> { DiagnosticEventJson.decode("not json") }

        val obj = JsonParser.parseString(line).asJsonObject
        obj.addProperty("stage", "bogus")
        assertFailsWith<IllegalArgumentException> { DiagnosticEventJson.decode(obj.toString()) }
    }

    @Test
    fun decode_rejectsOutcomeErrorCodeMismatch() {
        val ctx = DiagnosticContext.start()
        val line = DiagnosticEventJson.encode(ctx.event(DiagnosticStage.LOGIN, BusinessResult.Success, timestampEpochMillis = 1L))
        val obj = JsonParser.parseString(line).asJsonObject
        obj.addProperty("outcome", "failure")
        // failure without an error code is malformed
        assertFailsWith<IllegalArgumentException> { DiagnosticEventJson.decode(obj.toString()) }
    }

    @Test
    fun decode_roundTripsEveryLayerAndOutcome() {
        val ctx = DiagnosticContext.start()
        val events = listOf(
            ctx.event(DiagnosticStage.LOGIN, TransportResult.Failure(TransportErrorCode.CONNECT_TIMEOUT, "timeout"), 1L, 2L, false, null),
            ctx.event(DiagnosticStage.LOGIN, HttpResult.Failure(HttpErrorCode.MISSING_LOCATION, "no location"), 1L, 2L, false, null),
            ctx.event(DiagnosticStage.LOGIN, ProtocolResult.Failure(ProtocolErrorCode.DECRYPT, "decrypt fail"), 1L, 2L, false, null),
            ctx.event(DiagnosticStage.LOGIN, BusinessResult.Failure(BusinessErrorCode.UNAUTHORIZED, "unauthorized"), 1L, 2L, false, null),
            ctx.event(DiagnosticStage.SMS, BusinessResult.Success, 1L, 2L, false, null),
        )
        val jsonl = DiagnosticEventJson.encodeLines(events)
        assertEquals(events, DiagnosticEventJson.decodeLines(jsonl))
    }
}
