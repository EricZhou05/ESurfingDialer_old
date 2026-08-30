package com.rsplwe.esurfing.utils

import com.rsplwe.esurfing.States
import com.rsplwe.esurfing.diagnostics.DiagnosticStage
import com.rsplwe.esurfing.fixture.FixtureLoader
import com.rsplwe.esurfing.network.NetDiagnostics
import com.rsplwe.esurfing.protocol.SmsQueryFailure
import com.rsplwe.esurfing.protocol.SmsVerifyOutcome
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SMS query contract: requestVerifyCode / checkVerifyCodeStatus / getVerifyCode
 * return typed outcomes. "Needs a code", "no code needed" and "query failed"
 * are three distinct verdicts — and HTTP errors, malformed JSON, missing
 * endpoints and transport failures each carry their own [SmsQueryFailure].
 */
class SmsVerifyOutcomeTest {

    private var server: MockWebServer? = null

    @BeforeEach
    fun setUp() {
        NetDiagnostics.resetForTest()
        States.extraCfgUrl = HashMap()
        States.schoolId = "fixture-school-0001"
        States.area = ""
        States.domain = ""
    }

    @AfterEach
    fun tearDown() {
        server?.shutdown()
    }

    private fun startServer(): String {
        val s = MockWebServer()
        server = s
        s.start()
        return s.url("/sms").toString()
    }

    private fun statusOutcome(response: MockResponse): SmsVerifyOutcome {
        States.extraCfgUrl["QueryVerificateCodeStatus"] = startServer()
        server!!.enqueue(response)
        return checkVerifyCodeStatus("13800000000")
    }

    @Test
    fun status_matchingRescode_isRequiresVerification() {
        val out = statusOutcome(MockResponse().setResponseCode(200).setBody(FixtureLoader.text("json/sms-status-requires.json")))
        assertTrue(out is SmsVerifyOutcome.RequiresVerification, "matching rescode must require, got: $out")
        val event = NetDiagnostics.events().last()
        assertEquals(DiagnosticStage.SMS, event.stage)
        assertEquals("business.requires_sms", event.errorCode)
    }

    @Test
    fun status_otherRescode_isNotRequired() {
        val out = statusOutcome(MockResponse().setResponseCode(200).setBody(FixtureLoader.text("json/sms-status-not-required.json")))
        assertTrue(out is SmsVerifyOutcome.NotRequired, "'no code' must be distinct, got: $out")
    }

    @Test
    fun http500_isQueryFailedHttpError() {
        val out = statusOutcome(MockResponse().setResponseCode(500).setBody("<html>boom</html>"))
        assertTrue(out is SmsVerifyOutcome.QueryFailed, "HTTP 500 must be a query failure, got: $out")
        assertEquals(SmsQueryFailure.HTTP_ERROR, (out as SmsVerifyOutcome.QueryFailed).reason)
    }

    @Test
    fun malformedBody_isQueryFailedMalformed() {
        val out = statusOutcome(MockResponse().setResponseCode(200).setBody(FixtureLoader.text("json/sms-malformed.json")))
        assertTrue(out is SmsVerifyOutcome.QueryFailed, "malformed JSON must fail, got: $out")
        assertEquals(SmsQueryFailure.MALFORMED_RESPONSE, (out as SmsVerifyOutcome.QueryFailed).reason)
        val event = NetDiagnostics.events().last()
        assertEquals("business.sms_query_failed", event.errorCode)
    }

    @Test
    fun emptyBody_isQueryFailedMalformed() {
        val out = statusOutcome(MockResponse().setResponseCode(200).setBody(""))
        assertTrue(out is SmsVerifyOutcome.QueryFailed, "empty body must fail, got: $out")
        assertEquals(SmsQueryFailure.MALFORMED_RESPONSE, (out as SmsVerifyOutcome.QueryFailed).reason)
    }

    @Test
    fun missingRescode_isQueryFailedMalformed() {
        val out = statusOutcome(MockResponse().setResponseCode(200).setBody("""{"fictitious":true,"phone":"13800000000"}"""))
        assertTrue(out is SmsVerifyOutcome.QueryFailed, "missing rescode must fail, got: $out")
        assertEquals(SmsQueryFailure.MALFORMED_RESPONSE, (out as SmsVerifyOutcome.QueryFailed).reason)
    }

    @Test
    fun missingEndpoint_isQueryFailedEndpointMissing() {
        States.extraCfgUrl = HashMap()
        val out = checkVerifyCodeStatus("13800000000")
        assertTrue(out is SmsVerifyOutcome.QueryFailed, "no endpoint must fail, got: $out")
        assertEquals(SmsQueryFailure.ENDPOINT_MISSING, (out as SmsVerifyOutcome.QueryFailed).reason)
    }

    @Test
    fun transportFailure_isQueryFailedTransport() {
        val url = startServer()
        server!!.shutdown()
        States.extraCfgUrl["QueryVerificateCodeStatus"] = url
        val out = checkVerifyCodeStatus("13800000000")
        assertTrue(out is SmsVerifyOutcome.QueryFailed, "refused connection must fail, got: $out")
        assertEquals(SmsQueryFailure.TRANSPORT, (out as SmsVerifyOutcome.QueryFailed).reason)
    }

    @Test
    fun getVerifyCode_matchingRescode_isRequiresVerification() {
        States.extraCfgUrl["QueryAuthCode"] = startServer()
        server!!.enqueue(MockResponse().setResponseCode(200).setBody(FixtureLoader.text("json/sms-code-issued.json")))
        val out = getVerifyCode("13800000000")
        assertTrue(out is SmsVerifyOutcome.RequiresVerification, "code issued must require input, got: $out")
    }

    @Test
    fun getVerifyCode_otherRescode_isNotRequired() {
        States.extraCfgUrl["QueryAuthCode"] = startServer()
        server!!.enqueue(MockResponse().setResponseCode(200).setBody(FixtureLoader.text("json/sms-code-not-required.json")))
        val out = getVerifyCode("13800000000")
        assertTrue(out is SmsVerifyOutcome.NotRequired, "already verified must not require, got: $out")
    }
}