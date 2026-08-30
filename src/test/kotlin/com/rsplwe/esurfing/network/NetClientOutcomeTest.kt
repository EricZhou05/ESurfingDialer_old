package com.rsplwe.esurfing.network

import com.rsplwe.esurfing.States
import com.rsplwe.esurfing.diagnostics.DiagnosticStage
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Classification matrix for NetClient.post over MockWebServer: every input
 * class must resolve to a distinct typed outcome, non-2xx / redirect-limit /
 * empty-body / transport failures never fall into NetResult.Success, and the
 * exchange record carries the sanitised diagnostic snapshot.
 */
class NetClientOutcomeTest {

    private var server: MockWebServer? = null

    @BeforeEach
    fun setUp() {
        States.schoolId = ""
        States.domain = ""
        States.area = ""
        States.ticketUrl = ""
        States.authUrl = ""
    }

    @AfterEach
    fun tearDown() {
        server?.shutdown()
    }

    private fun serverUrl(path: String = "/auth"): String {
        val s = MockWebServer()
        server = s
        s.start()
        return s.url(path).toString()
    }

    private fun errorCode(result: NetResult<*>): String? =
        (result as? NetResult.Error)?.exception

    // --- 2xx ---

    @Test
    fun http200_withBody_isSuccess() {
        val url = serverUrl()
        server!!.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        val result = post(url, "payload=1")
        assertTrue(result is NetResult.Success, "HTTP 200 must be Success, got: $result")
        assertNull(errorCode(result))
        result.data.close()
    }

    @Test
    fun http200_carriesSanitisedExchangeRecord() {
        val url = serverUrl()
        server!!.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        val result = post("$url?secret=canary&wlanuserip=1.2.3.4", "payload=1")
        assertTrue(result is NetResult.Success, "expected Success, got: $result")
        val exchange = result.exchange
        assertNotNull(exchange, "Success must carry the exchange record")
        assertTrue(
            !exchange!!.safeUrl.contains("secret") && !exchange.safeUrl.contains("wlanuserip"),
            "safeUrl must strip the query, got: ${exchange.safeUrl}"
        )
        assertTrue(
            exchange.safeUrl.startsWith(url) || exchange.safeUrl.endsWith("/auth"),
            "safeUrl must keep scheme/host/path, got: ${exchange.safeUrl}"
        )
        assertEquals(200, exchange.status)
        assertEquals(0, exchange.redirectChain.size)
        result.data.close()
    }

    // --- empty body ---

    @Test
    fun http204_noContent_isEmptyBodyError() {
        val url = serverUrl()
        server!!.enqueue(MockResponse().setResponseCode(204))
        val result = post(url, "payload=1")
        assertTrue(result is NetResult.Error, "HTTP 204 must be Error, got: $result")
        assertEquals("EMPTY_BODY", errorCode(result))
    }

    @Test
    fun http200_withEmptyBody_isEmptyBodyError() {
        val url = serverUrl()
        server!!.enqueue(MockResponse().setResponseCode(200).setBody(""))
        val result = post(url, "payload=1")
        assertTrue(result is NetResult.Error, "empty 200 must be Error, got: $result")
        assertEquals("EMPTY_BODY", errorCode(result))
    }

    // --- non-2xx statuses ---

    @Test
    fun http400_isClassifiedHttpError() {
        val url = serverUrl()
        server!!.enqueue(MockResponse().setResponseCode(400).setBody("bad request"))
        val result = post(url, "payload=1")
        assertTrue(result is NetResult.Error, "HTTP 400 must be Error, got: $result")
        assertEquals("HTTP_400", errorCode(result))
    }

    @Test
    fun http401_isClassifiedHttpError() {
        val url = serverUrl()
        server!!.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))
        val result = post(url, "payload=1")
        assertTrue(result is NetResult.Error, "HTTP 401 must be Error, got: $result")
        assertEquals("HTTP_401", errorCode(result))
    }

    @Test
    fun http500_isClassifiedHttpError() {
        val url = serverUrl()
        server!!.enqueue(MockResponse().setResponseCode(500).setBody("<html>boom</html>"))
        val result = post(url, "payload=1")
        assertTrue(result is NetResult.Error, "HTTP 500 must be Error, got: $result")
        assertEquals("HTTP_500", errorCode(result))
        assertEquals(500, (result as NetResult.Error).exchange?.status)
    }

    // --- redirects ---

    private fun redirectTo(location: String, vararg headers: Pair<String, String>): MockResponse {
        val response = MockResponse().setResponseCode(302).addHeader("Location", location)
        headers.forEach { (k, v) -> response.addHeader(k, v) }
        return response
    }

    @Test
    fun redirect_302_followedTo200_isSuccess() {
        val url = serverUrl()
        server!!.enqueue(redirectTo("${url.replace("/auth", "/final")}"))
        server!!.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        val result = post(url, "payload=1")
        assertTrue(result is NetResult.Success, "redirect to 200 must be Success, got: $result")
        result.data.close()
    }

    @Test
    fun redirect_307_followedTo200_isSuccess() {
        val url = serverUrl()
        server!!.enqueue(MockResponse().setResponseCode(307).addHeader("Location", url.replace("/auth", "/final")))
        server!!.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        val result = post(url, "payload=1")
        assertTrue(result is NetResult.Success, "307 to 200 must be Success, got: $result")
        result.data.close()
    }

    @Test
    fun redirect_308_followedTo200_isSuccess() {
        val url = serverUrl()
        server!!.enqueue(MockResponse().setResponseCode(308).addHeader("Location", url.replace("/auth", "/final")))
        server!!.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        val result = post(url, "payload=1")
        assertTrue(result is NetResult.Success, "308 to 200 must be Success, got: $result")
        result.data.close()
    }

    @Test
    fun redirect_eachHopUpdatesCdcHeadersAndChain() {
        val url = serverUrl()
        server!!.enqueue(redirectTo(url.replace("/auth", "/h1"), "area" to "a1"))
        server!!.enqueue(redirectTo(url.replace("/auth", "/h2"), "area" to "a2", "schoolid" to "s2"))
        server!!.enqueue(redirectTo(url.replace("/auth", "/h3"), "domain" to "d3"))
        server!!.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        val result = post(url, "payload=1")
        assertTrue(result is NetResult.Success, "3 redirects then 200 must be Success, got: $result")
        assertEquals("a2", States.area, "last area hop must win")
        assertEquals("s2", States.schoolId)
        assertEquals("d3", States.domain)
        val chain = result.exchange!!.redirectChain
        assertEquals(3, chain.size, "redirect chain must capture all three hops")
        assertEquals("a1", chain[0].area)
        assertEquals("a2", chain[1].area)
        assertEquals("s2", chain[1].schoolId)
        assertEquals("d3", chain[2].domain)
        result.data.close()
    }

    @Test
    fun redirect_moreThanFiveHops_isRedirectLimitError() {
        val url = serverUrl()
        val final = url.replace("/auth", "/final")
        repeat(6) { i -> server!!.enqueue(redirectTo(final)) }
        val result = post(url, "payload=1")
        assertTrue(result is NetResult.Error, "6 redirects must hit the limit, got: $result")
        assertEquals("REDIRECT_LIMIT", errorCode(result))
    }

    @Test
    fun redirect_withoutLocation_isMissingLocationError() {
        val url = serverUrl()
        server!!.enqueue(MockResponse().setResponseCode(302))
        val result = post(url, "payload=1")
        assertTrue(result is NetResult.Error, "302 without Location must be Error, got: $result")
        assertEquals("MISSING_LOCATION", errorCode(result))
    }

    // --- transport failures ---

    @Test
    fun timeout_noResponse_isTimeoutError() {
        val url = serverUrl()
        server!!.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val original = apiClient
        try {
            apiClient = createHttpClient(timeoutSeconds = 1)
            val result = post(url, "payload=1")
            assertTrue(result is NetResult.Error, "timeout must be Error, got: $result")
            assertEquals("TIMEOUT", errorCode(result))
        } finally {
            apiClient = original
        }
    }

    @Test
    fun unknownHostException_mapsToDnsError() {
        val result = classifyFailure(
            UnknownHostException("nxdomain"),
            DiagnosticStage.SESSION_ZSM,
            "http://host.invalid/portal",
            0L,
        )
        assertTrue(result is NetResult.Error, "DNS failure must be Error, got: $result")
        assertEquals(NetErrorCode.DNS, errorCode(result))
    }

    @Test
    fun unreachableHost_isNeverReportedAsSuccess() {
        // CI sandbox resolves everything via wildcard DNS; the exact transport
        // exception is environment-dependent (DNS or IO) — only "never Success"
        // is contract; the deterministic mapping is covered by the unit test above.
        val result = post("http://definitely-not-a-real-host.invalid/portal", "payload=1")
        assertTrue(result is NetResult.Error, "unreachable host must be Error, got: $result")
    }

    @Test
    fun connectionRefused_isConnectionRefusedError() {
        val s = MockWebServer()
        server = s
        s.start()
        val url = s.url("/auth").toString()
        s.shutdown()
        val result = post(url, "payload=1")
        assertTrue(result is NetResult.Error, "connection refused must be Error, got: $result")
        assertEquals("CONNECTION_REFUSED", errorCode(result))
    }
}