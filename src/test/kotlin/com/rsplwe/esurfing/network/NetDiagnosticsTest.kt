package com.rsplwe.esurfing.network

import com.rsplwe.esurfing.diagnostics.DiagnosticStage
import com.rsplwe.esurfing.diagnostics.HttpResult
import com.rsplwe.esurfing.diagnostics.ResultLayer
import com.rsplwe.esurfing.diagnostics.ResultOutcome
import com.rsplwe.esurfing.diagnostics.TransportResult
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end wiring from the network layer into the Todo-2 diagnostics
 * contract: every HTTP/transport outcome must be recorded as one typed
 * DiagnosticEvent carrying a four-layer result, and the stage must be
 * selectable for post().
 */
class NetDiagnosticsTest {

    private var server: MockWebServer? = null

    @BeforeEach
    fun setUp() {
        NetDiagnostics.resetForTest()
    }

    @AfterEach
    fun tearDown() {
        server?.shutdown()
    }

    private fun startServer(): String {
        val s = MockWebServer()
        server = s
        s.start()
        return s.url("/auth").toString()
    }

    @Test
    fun post200_recordsHttpSuccessEvent() {
        val url = startServer()
        server!!.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        val result = NetDiagnostics.withStage(DiagnosticStage.TICKET) { post(url, "p=1") }
        assertTrue(result is NetResult.Success)
        result.data.close()

        val events = NetDiagnostics.events()
        assertEquals(1, events.size, "one post must record exactly one event")
        val event = events.single()
        assertEquals(DiagnosticStage.TICKET, event.stage)
        assertEquals(ResultLayer.HTTP, event.layer)
        assertEquals(ResultOutcome.SUCCESS, event.outcome)
        assertNull(event.errorCode)
        assertTrue(event.result is HttpResult.Success)
    }

    @Test
    fun post404_recordsTypedHttpFailureEvent() {
        val url = startServer()
        server!!.enqueue(MockResponse().setResponseCode(404).setBody("nope"))
        val result = post(url, "p=1")
        assertTrue(result is NetResult.Error, "got: $result")

        val event = NetDiagnostics.events().single()
        assertEquals(ResultLayer.HTTP, event.layer)
        assertEquals(ResultOutcome.FAILURE, event.outcome)
        assertEquals("http.non_2xx", event.errorCode)
        assertEquals("HTTP_404", event.safeSummary)
    }

    @Test
    fun postTimeout_recordsTransportFailureEvent() {
        val url = startServer()
        server!!.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val original = apiClient
        try {
            apiClient = createHttpClient(timeoutSeconds = 1)
            val result = post(url, "p=1")
            assertTrue(result is NetResult.Error, "got: $result")
        } finally {
            apiClient = original
        }
        val event = NetDiagnostics.events().single()
        assertEquals(ResultLayer.TRANSPORT, event.layer)
        assertEquals(ResultOutcome.FAILURE, event.outcome)
        assertEquals("transport.read_timeout", event.errorCode)
        assertTrue(event.result is TransportResult.Failure)
    }

    @Test
    fun redirectLimit_recordsHttpRedirectLimitEvent() {
        val url = startServer()
        val final = url.replace("/auth", "/final")
        repeat(6) { server!!.enqueue(MockResponse().setResponseCode(302).addHeader("Location", final)) }
        val result = post(url, "p=1")
        assertTrue(result is NetResult.Error, "got: $result")
        assertEquals("http.redirect_limit", NetDiagnostics.events().single().errorCode)
    }

    @Test
    fun postUsesDefaultStage_whenNotSelected() {
        val url = startServer()
        server!!.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        val result = post(url, "p=1")
        assertTrue(result is NetResult.Success)
        result.data.close()
        assertEquals(DiagnosticStage.SESSION_ZSM, NetDiagnostics.events().single().stage)
    }

    @Test
    fun eventsOfOneRun_shareContextIds() {
        val url = startServer()
        server!!.enqueue(MockResponse().setResponseCode(200).setBody("a"))
        server!!.enqueue(MockResponse().setResponseCode(404).setBody("b"))
        val first = post(url, "p=1")
        assertTrue(first is NetResult.Success)
        first.data.close()
        val second = post(url, "p=2")
        assertTrue(second is NetResult.Error)

        val events = NetDiagnostics.events()
        assertEquals(2, events.size)
        val runId = events[0].runId
        val attemptId = events[0].attemptId
        val correlationId = events[0].correlationId
        for (event in events) {
            assertEquals(runId, event.runId)
            assertEquals(attemptId, event.attemptId)
            assertEquals(correlationId, event.correlationId)
        }
        assertEquals(runId, NetDiagnostics.context().runId)
    }
}