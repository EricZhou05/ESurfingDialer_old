package com.rsplwe.esurfing.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Exhaustive assertions over the four-layer typed results: every layer exposes
 * a Success branch and a Failure branch carrying a typed error code, every
 * error code round-trips through its wire code, and each layer is exhaustively
 * matchable.
 */
class DiagnosticEventResultTest {

    // --- layers ---

    @Test
    fun layerSet_matchesContract() {
        assertEquals(
            listOf("transport", "http", "protocol", "business"),
            ResultLayer.entries.map { it.wire },
            "result layers must be transport/http/protocol/business in order"
        )
    }

    @Test
    fun everyLayer_roundTripsThroughWireName() {
        for (layer in ResultLayer.entries) {
            assertEquals(layer, ResultLayer.fromWire(layer.wire))
        }
        assertNull(ResultLayer.fromWire("nope"))
    }

    @Test
    fun outcomeSet_isSuccessAndFailureOnly() {
        assertEquals(listOf("success", "failure"), ResultOutcome.entries.map { it.wire })
        assertEquals(ResultOutcome.SUCCESS, ResultOutcome.fromWire("success"))
        assertEquals(ResultOutcome.FAILURE, ResultOutcome.fromWire("failure"))
        assertNull(ResultOutcome.fromWire("ok"))
    }

    // --- transport ---

    @Test
    fun transportSuccess_isSingletonWithNullErrorCode() {
        assertEquals(ResultLayer.TRANSPORT, TransportResult.Success.layer)
        assertEquals(ResultOutcome.SUCCESS, TransportResult.Success.outcome)
        assertNull(TransportResult.Success.errorCode)
    }

    @Test
    fun transportFailure_everyErrorCodeRoundTrips() {
        for (code in TransportErrorCode.entries) {
            assertEquals(code, TransportErrorCode.fromCode(code.code))
            val failure = TransportResult.Failure(code, "summary")
            assertEquals(ResultLayer.TRANSPORT, failure.layer)
            assertEquals(ResultOutcome.FAILURE, failure.outcome)
            assertEquals(code, failure.errorCode)
        }
    }

    @Test
    fun transportResult_isExhaustivelyMatchable() {
        fun describe(r: TransportResult): String = when (r) {
            is TransportResult.Success -> "success"
            is TransportResult.Failure -> "failure:${r.errorCode.code}"
        }
        assertEquals("success", describe(TransportResult.Success))
        assertEquals("failure:transport.io", describe(TransportResult.Failure(TransportErrorCode.IO, "io")))
    }

    // --- http ---

    @Test
    fun httpSuccess_isSingletonWithNullErrorCode() {
        assertEquals(ResultLayer.HTTP, HttpResult.Success.layer)
        assertEquals(ResultOutcome.SUCCESS, HttpResult.Success.outcome)
        assertNull(HttpResult.Success.errorCode)
    }

    @Test
    fun httpFailure_everyErrorCodeRoundTrips() {
        for (code in HttpErrorCode.entries) {
            assertEquals(code, HttpErrorCode.fromCode(code.code))
            val failure = HttpResult.Failure(code, "summary")
            assertEquals(ResultLayer.HTTP, failure.layer)
            assertEquals(ResultOutcome.FAILURE, failure.outcome)
            assertEquals(code, failure.errorCode)
        }
    }

    @Test
    fun httpResult_isExhaustivelyMatchable() {
        fun describe(r: HttpResult): String = when (r) {
            is HttpResult.Success -> "success"
            is HttpResult.Failure -> "failure:${r.errorCode.code}"
        }
        assertEquals("failure:http.non_2xx", describe(HttpResult.Failure(HttpErrorCode.NON_2XX, "500")))
    }

    // --- protocol ---

    @Test
    fun protocolSuccess_isSingletonWithNullErrorCode() {
        assertEquals(ResultLayer.PROTOCOL, ProtocolResult.Success.layer)
        assertEquals(ResultOutcome.SUCCESS, ProtocolResult.Success.outcome)
        assertNull(ProtocolResult.Success.errorCode)
    }

    @Test
    fun protocolFailure_everyErrorCodeRoundTrips() {
        for (code in ProtocolErrorCode.entries) {
            assertEquals(code, ProtocolErrorCode.fromCode(code.code))
            val failure = ProtocolResult.Failure(code, "summary")
            assertEquals(ResultLayer.PROTOCOL, failure.layer)
            assertEquals(ResultOutcome.FAILURE, failure.outcome)
            assertEquals(code, failure.errorCode)
        }
    }

    @Test
    fun protocolResult_isExhaustivelyMatchable() {
        fun describe(r: ProtocolResult): String = when (r) {
            is ProtocolResult.Success -> "success"
            is ProtocolResult.Failure -> "failure:${r.errorCode.code}"
        }
        assertEquals(
            "failure:protocol.unknown_algo_id",
            describe(ProtocolResult.Failure(ProtocolErrorCode.UNKNOWN_ALGO_ID, "unknown"))
        )
    }

    // --- business ---

    @Test
    fun businessSuccess_isSingletonWithNullErrorCode() {
        assertEquals(ResultLayer.BUSINESS, BusinessResult.Success.layer)
        assertEquals(ResultOutcome.SUCCESS, BusinessResult.Success.outcome)
        assertNull(BusinessResult.Success.errorCode)
    }

    @Test
    fun businessFailure_everyErrorCodeRoundTrips() {
        for (code in BusinessErrorCode.entries) {
            assertEquals(code, BusinessErrorCode.fromCode(code.code))
            val failure = BusinessResult.Failure(code, "summary")
            assertEquals(ResultLayer.BUSINESS, failure.layer)
            assertEquals(ResultOutcome.FAILURE, failure.outcome)
            assertEquals(code, failure.errorCode)
        }
    }

    @Test
    fun businessResult_isExhaustivelyMatchable() {
        fun describe(r: BusinessResult): String = when (r) {
            is BusinessResult.Success -> "success"
            is BusinessResult.Failure -> "failure:${r.errorCode.code}"
        }
        assertEquals(
            "failure:business.requires_sms",
            describe(BusinessResult.Failure(BusinessErrorCode.REQUIRES_SMS, "sms needed"))
        )
    }

    // --- unified DiagnosticResult ---

    @Test
    fun everyLayerSuccess_fitsUnifiedDiagnosticResult() {
        val all: List<DiagnosticResult> = listOf(
            TransportResult.Success,
            HttpResult.Success,
            ProtocolResult.Success,
            BusinessResult.Success,
        )
        for (result in all) {
            assertEquals(ResultOutcome.SUCCESS, result.outcome)
            assertNull(result.errorCode)
        }
    }
}
