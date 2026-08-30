package com.rsplwe.esurfing.utils

import com.rsplwe.esurfing.Constants
import com.rsplwe.esurfing.States
import com.rsplwe.esurfing.network.NetDiagnostics
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Portal probe (detectConfig) classification. The critical contract: a 200 that
 * no longer carries the historic portal marker must NOT be reported as connected
 * (SUCCESS) — that is the Todo-1 red defect this Todo fixes. CDC area/schoolId/
 * domain updates per redirect hop are preserved and locked by tests.
 */
class PortalDetectTest {

    private var server: MockWebServer? = null
    private var originalCaptiveUrl: String = Constants.CAPTIVE_URL

    @BeforeEach
    fun setUp() {
        NetDiagnostics.resetForTest()
        States.authUrl = ""
        States.ticketUrl = ""
        States.userIp = ""
        States.acIp = ""
        States.schoolId = ""
        States.domain = ""
        States.area = ""
        States.extraCfgUrl = HashMap()
        originalCaptiveUrl = Constants.CAPTIVE_URL
        val s = MockWebServer()
        server = s
        s.start()
        Constants.CAPTIVE_URL = s.url("/generate_204").toString()
    }

    @AfterEach
    fun tearDown() {
        Constants.CAPTIVE_URL = originalCaptiveUrl
        server?.shutdown()
    }

    private fun portalBodyWith(ticketUrl: String): String = """
        <!DOCTYPE html>
        <html><body>
        <!--//config.campus.js.chinatelecom.com
        <config>
            <auth-url>http://10.99.98.97/portal/auth</auth-url>
            <ticket-url>$ticketUrl</ticket-url>
            <funcfg><wlanacip enable="1" url="http://10.99.98.97/portal/acip"/></funcfg>
        </config>
        //config.campus.js.chinatelecom.com-->
        </body></html>
    """.trimIndent()

    private fun markerMissingBody(): String = """
        <!DOCTYPE html>
        <html><body><h1>connected</h1></body></html>
    """.trimIndent()

    private fun lastEventCode(): String? = NetDiagnostics.events().lastOrNull()?.errorCode

    @Test
    fun portalWithConfig_returnsRequireAuthorization_andPopulatesStates() {
        val ticketUrl = server!!.url("/ticket?wlanuserip=1.2.3.4&wlanacip=5.6.7.8").toString()
        server!!.enqueue(MockResponse().setResponseCode(200).setBody(portalBodyWith(ticketUrl)))
        val status = detectConfig()
        assertEquals(ConnectivityStatus.REQUIRE_AUTHORIZATION, status)
        assertEquals("http://10.99.98.97/portal/auth", States.authUrl)
        assertEquals(ticketUrl, States.ticketUrl)
        assertEquals("1.2.3.4", States.userIp)
        assertEquals("5.6.7.8", States.acIp)
        assertTrue(States.extraCfgUrl["wlanacip"] == "http://10.99.98.97/portal/acip")
    }

    @Test
    fun portalResponse_withoutMarker_isNotReportedAsConnected() {
        server!!.enqueue(MockResponse().setResponseCode(200).setBody(markerMissingBody()))
        val status = detectConfig()
        assertTrue(
            status != ConnectivityStatus.SUCCESS,
            "HTTP 200 without the portal marker must not be reported as connected, got: $status"
        )
        assertEquals(ConnectivityStatus.REQUEST_ERROR, status)
        assertEquals("protocol.missing_field", lastEventCode(), "marker-missing must be typed as a protocol failure")
    }

    @Test
    fun portalResponse_withOnlyStartTag_isNotConnected() {
        val body = "<!--//config.campus.js.chinatelecom.com\nvar C = {};" // no end tag
        server!!.enqueue(MockResponse().setResponseCode(200).setBody(body))
        val status = detectConfig()
        assertTrue(
            status != ConnectivityStatus.SUCCESS,
            "incomplete marker must not be reported as connected, got: $status"
        )
        assertEquals(ConnectivityStatus.REQUEST_ERROR, status)
    }

    @Test
    fun portalHttp500_isRequestError_neverSuccess() {
        server!!.enqueue(MockResponse().setResponseCode(500).setBody("<html>boom</html>"))
        val status = detectConfig()
        assertTrue(status != ConnectivityStatus.SUCCESS, "HTTP 500 must never report connected, got: $status")
        assertEquals(ConnectivityStatus.REQUEST_ERROR, status)
        assertEquals("http.non_2xx", lastEventCode())
    }

    @Test
    fun portalEmptyBody_isRequestError_neverSuccess() {
        server!!.enqueue(MockResponse().setResponseCode(200).setBody(""))
        val status = detectConfig()
        assertTrue(status != ConnectivityStatus.SUCCESS, "empty portal body must never report connected, got: $status")
        assertEquals(ConnectivityStatus.REQUEST_ERROR, status)
        assertEquals("http.empty_body", lastEventCode())
    }

    @Test
    fun portalConfig_missingAuthOrTicketUrl_isRequestError() {
        val body = """
            <!--//config.campus.js.chinatelecom.com
            <config><auth-url></auth-url></config>
            //config.campus.js.chinatelecom.com-->
        """.trimIndent()
        server!!.enqueue(MockResponse().setResponseCode(200).setBody(body))
        val status = detectConfig()
        assertTrue(status != ConnectivityStatus.SUCCESS, "missing auth-url must not report connected, got: $status")
        assertEquals(ConnectivityStatus.REQUEST_ERROR, status)
    }

    @Test
    fun portalConfig_missingUserIpOrAcIp_isRequestError() {
        val ticketUrl = server!!.url("/ticket").toString() // no wlanuserip / wlanacip query
        server!!.enqueue(MockResponse().setResponseCode(200).setBody(portalBodyWith(ticketUrl)))
        val status = detectConfig()
        assertTrue(status != ConnectivityStatus.SUCCESS, "missing userIp/acIp must not report connected, got: $status")
        assertEquals(ConnectivityStatus.REQUEST_ERROR, status)
    }

    @Test
    fun portalRedirect_eachHopUpdatesCdcHeaders() {
        val finalUrl = server!!.url("/portal?wlanuserip=9.8.7.6&wlanacip=1.2.3.4").toString()
        server!!.enqueue(
            MockResponse().setResponseCode(302)
                .addHeader("Location", finalUrl)
                .addHeader("area", "portal-area")
                .addHeader("schoolid", "portal-school")
                .addHeader("domain", "portal.example.invalid")
        )
        server!!.enqueue(MockResponse().setResponseCode(200).setBody(portalBodyWith(finalUrl)))
        val status = detectConfig()
        assertEquals(ConnectivityStatus.REQUIRE_AUTHORIZATION, status)
        assertEquals("portal-area", States.area, "redirect hop must update area")
        assertEquals("portal-school", States.schoolId)
        assertEquals("portal.example.invalid", States.domain)
    }
}