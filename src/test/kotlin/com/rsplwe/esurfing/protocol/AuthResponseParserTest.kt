package com.rsplwe.esurfing.protocol

import com.rsplwe.esurfing.fixture.FixtureLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract matrix for the XML response parser: non-XML, truncated, duplicate,
 * empty and blank fields, unknown business codes and invalid retry/interval
 * values must each produce the typed failure branch they describe — no "" or
 * bool collapses and no `toLong()` crash is reachable.
 */
class AuthResponseParserTest {

    private fun xml(result: String, vararg fields: Pair<String, String>): String = buildString {
        append("""<?xml version="1.0" encoding="utf-8"?>""")
        append("<response><result>").append(result).append("</result>")
        fields.forEach { (name, value) ->
            append("<").append(name).append(">").append(value).append("</").append(name).append(">")
        }
        append("</response>")
    }

    private fun loginFailReason(raw: String): ContractFailure =
        (AuthResponseParser.parseLogin(raw) as LoginParseResult.Failure).reason

    private fun loginFailSummary(raw: String): String =
        (AuthResponseParser.parseLogin(raw) as LoginParseResult.Failure).safeSummary

    // --- ticket ---

    @Test
    fun ticket_valid_isSuccess() {
        val out = AuthResponseParser.parseTicket(FixtureLoader.text("xml/ticket-response.xml"))
        assertTrue(out is TicketParseResult.Success, "expected Success, got: $out")
        assertEquals("FIXTURE_TICKET_0000000000000000", (out as TicketParseResult.Success).ticket)
    }

    @Test
    fun ticket_notXml_isFormatMalformed() {
        val out = AuthResponseParser.parseTicket(FixtureLoader.text("xml/ticket-response-nonxml.txt"))
        assertTrue(out is TicketParseResult.Failure, "non-XML must fail, got: $out")
        assertEquals(ContractFailure.FORMAT_MALFORMED, (out as TicketParseResult.Failure).reason)
    }

    @Test
    fun ticket_truncated_isMissingField() {
        val out = AuthResponseParser.parseTicket(FixtureLoader.text("xml/ticket-response-truncated.xml"))
        assertTrue(out is TicketParseResult.Failure, "truncated must fail, got: $out")
        assertEquals(ContractFailure.MISSING_FIELD, (out as TicketParseResult.Failure).reason)
    }

    @Test
    fun ticket_emptyField_isMissingField() {
        val out = AuthResponseParser.parseTicket(FixtureLoader.text("xml/ticket-response-empty-ticket.xml"))
        assertTrue(out is TicketParseResult.Failure, "empty ticket must fail, got: $out")
        assertEquals(ContractFailure.MISSING_FIELD, (out as TicketParseResult.Failure).reason)
    }

    @Test
    fun ticket_duplicate_firstNonBlankWins() {
        val out = AuthResponseParser.parseTicket(FixtureLoader.text("xml/ticket-response-duplicate.xml"))
        assertTrue(out is TicketParseResult.Success, "duplicates are deterministic, got: $out")
        assertEquals("FIXTURE_TICKET_FIRST_DUPLICATE", (out as TicketParseResult.Success).ticket)
    }

    @Test
    fun ticket_unknownBusinessCode_isBusinessRejected() {
        val out = AuthResponseParser.parseTicket(FixtureLoader.text("xml/ticket-response-rejected.xml"))
        assertTrue(out is TicketParseResult.Failure, "rejected must fail, got: $out")
        assertEquals(ContractFailure.BUSINESS_REJECTED, (out as TicketParseResult.Failure).reason)
    }

    @Test
    fun ticket_absentResultTag_isMissingField() {
        val out = AuthResponseParser.parseTicket("<response><ticket>FIXTURE_TICKET_X</ticket></response>")
        assertEquals(ContractFailure.MISSING_FIELD, (out as TicketParseResult.Failure).reason)
    }

    // --- login ---

    @Test
    fun login_valid_parsesAllFields() {
        val out = AuthResponseParser.parseLogin(FixtureLoader.text("xml/login-response.xml"))
        assertTrue(out is LoginParseResult.Success, "expected Success, got: $out")
        assertEquals("http://fixture.example.invalid/portal/keep", (out as LoginParseResult.Success).keepUrl)
        assertEquals("http://fixture.example.invalid/portal/term", out.termUrl)
        assertEquals(30L, out.keepRetrySeconds)
    }

    @Test
    fun login_missingAllFields_isMissingField() {
        assertEquals(
            ContractFailure.MISSING_FIELD,
            loginFailReason(FixtureLoader.text("xml/login-response-missing-fields.xml")),
        )
    }

    @Test
    fun login_missingKeepRetry_isMissingField() {
        assertEquals(
            ContractFailure.MISSING_FIELD,
            loginFailReason(FixtureLoader.text("xml/login-response-missing-retry.xml")),
        )
        assertTrue(loginFailSummary(FixtureLoader.text("xml/login-response-missing-retry.xml")).contains("login_keep_retry"))
    }

    @Test
    fun login_invalidKeepRetry_alpha_isInvalidInterval() {
        assertEquals(
            ContractFailure.INVALID_INTERVAL,
            loginFailReason(FixtureLoader.text("xml/login-response-invalid-retry.xml")),
        )
    }

    @Test
    fun login_keepRetryZero_isInvalidInterval() {
        assertEquals(ContractFailure.INVALID_INTERVAL, loginFailReason(FixtureLoader.text("xml/login-response-retry-zero.xml")))
    }

    @Test
    fun login_keepRetryNegative_isInvalidInterval() {
        assertEquals(ContractFailure.INVALID_INTERVAL, loginFailReason(FixtureLoader.text("xml/login-response-retry-negative.xml")))
    }

    @Test
    fun login_keepRetryOverflow_isInvalidInterval() {
        assertEquals(ContractFailure.INVALID_INTERVAL, loginFailReason(FixtureLoader.text("xml/login-response-retry-overflow.xml")))
    }

    @Test
    fun login_keepRetryAboveCap_isInvalidInterval() {
        assertEquals(ContractFailure.INVALID_INTERVAL, loginFailReason(FixtureLoader.text("xml/login-response-retry-too-large.xml")))
    }

    @Test
    fun login_keepRetryAtCapBoundaries() {
        assertEquals(3600L, (AuthResponseParser.parseLogin(xml("success", "keep-url" to "http://h.invalid/k", "term-url" to "http://h.invalid/t", "keep-retry" to "3600")) as LoginParseResult.Success).keepRetrySeconds)
        assertEquals(1L, (AuthResponseParser.parseLogin(xml("success", "keep-url" to "http://h.invalid/k", "term-url" to "http://h.invalid/t", "keep-retry" to "1")) as LoginParseResult.Success).keepRetrySeconds)
    }

    @Test
    fun login_indirectKeepUrl_isInvalidUrl() {
        assertEquals(
            ContractFailure.INVALID_URL,
            loginFailReason(
                xml("success", "keep-url" to "not-a-url", "term-url" to "http://h.invalid/t", "keep-retry" to "30")
            ),
        )
    }

    @Test
    fun login_missingKeepUrl_isMissingField() {
        val out = AuthResponseParser.parseLogin(FixtureLoader.text("xml/login-response-missing-keep-url.xml"))
        assertTrue(out is LoginParseResult.Failure, "missing keep-url must fail, got: $out")
        assertEquals(ContractFailure.MISSING_FIELD, (out as LoginParseResult.Failure).reason)
        assertEquals("login_keep_url_missing", (out as LoginParseResult.Failure).safeSummary)
    }

    @Test
    fun login_unknownBusinessCode_isBusinessRejected() {
        val out = AuthResponseParser.parseLogin(FixtureLoader.text("xml/login-response-rejected.xml"))
        assertTrue(out is LoginParseResult.Failure, "rejected login must fail, got: $out")
        assertEquals(ContractFailure.BUSINESS_REJECTED, (out as LoginParseResult.Failure).reason)
    }

    @Test
    fun login_notXml_isFormatMalformed() {
        assertEquals(ContractFailure.FORMAT_MALFORMED, loginFailReason("plaintext, definitely not XML"))
    }

    // --- heartbeat ---

    @Test
    fun heartbeat_valid_parsesInterval() {
        val out = AuthResponseParser.parseHeartbeat(FixtureLoader.text("xml/heartbeat-response.xml"))
        assertTrue(out is HeartbeatParseResult.Success, "expected Success, got: $out")
        assertEquals(30L, (out as HeartbeatParseResult.Success).intervalSeconds)
    }

    @Test
    fun heartbeat_blankInterval_isMissingField() {
        val out = AuthResponseParser.parseHeartbeat(FixtureLoader.text("xml/heartbeat-response-invalid-interval.xml"))
        assertEquals(ContractFailure.MISSING_FIELD, (out as HeartbeatParseResult.Failure).reason)
    }

    @Test
    fun heartbeat_zeroInterval_isInvalidInterval() {
        val out = AuthResponseParser.parseHeartbeat(FixtureLoader.text("xml/heartbeat-response-zero-interval.xml"))
        assertEquals(ContractFailure.INVALID_INTERVAL, (out as HeartbeatParseResult.Failure).reason)
    }

    @Test
    fun heartbeat_overflowInterval_isInvalidInterval() {
        val out = AuthResponseParser.parseHeartbeat(xml("success", "interval" to "99999999999999999999"))
        assertEquals(ContractFailure.INVALID_INTERVAL, (out as HeartbeatParseResult.Failure).reason)
    }

    @Test
    fun heartbeat_duplicateInterval_firstWins() {
        val out = AuthResponseParser.parseHeartbeat(
            "<response><result>success</result><interval>60</interval><interval>120</interval></response>"
        )
        assertTrue(out is HeartbeatParseResult.Success, "duplicates are deterministic, got: $out")
        assertEquals(60L, (out as HeartbeatParseResult.Success).intervalSeconds)
    }

    @Test
    fun heartbeat_unknownBusinessCode_isBusinessRejected() {
        val out = AuthResponseParser.parseHeartbeat(xml("fail", "interval" to "30"))
        assertEquals(ContractFailure.BUSINESS_REJECTED, (out as HeartbeatParseResult.Failure).reason)
    }

    @Test
    fun heartbeat_notXml_isFormatMalformed() {
        assertEquals(ContractFailure.FORMAT_MALFORMED, (AuthResponseParser.parseHeartbeat("garbage") as HeartbeatParseResult.Failure).reason)
    }

    // --- term ---

    @Test
    fun term_success_isSuccess() {
        assertTrue(AuthResponseParser.parseTerm(xml("success")) is TermParseResult.Success)
    }

    @Test
    fun term_rejected_isBusinessRejected() {
        assertEquals(ContractFailure.BUSINESS_REJECTED, (AuthResponseParser.parseTerm(xml("fail")) as TermParseResult.Failure).reason)
    }

    @Test
    fun term_notXml_isFormatMalformed() {
        assertEquals(ContractFailure.FORMAT_MALFORMED, (AuthResponseParser.parseTerm("junk") as TermParseResult.Failure).reason)
    }
}