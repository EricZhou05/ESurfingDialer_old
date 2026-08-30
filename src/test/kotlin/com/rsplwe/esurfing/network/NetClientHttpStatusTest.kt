package com.rsplwe.esurfing.network

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Contract test — a non-2xx HTTP response must be a NetResult.Error, never
 * NetResult.Success. Was written as a RED (TDD) test for Todo 1; the four
 * error-status tests were @Disabled until Todo 4 implemented the classification.
 * Now that Todo 4 is complete, they run and must pass (keep the 200 test green).
 */
class NetClientHttpStatusTest {

    private fun withServer(responseCode: Int, body: String, block: (String) -> Unit) {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(responseCode).setBody(body))
        server.start()
        try {
            block(server.url("/auth").toString())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun http500_isNotReportedAsSuccess() {
        withServer(500, "<html><body>Internal Server Error</body></html>") { url ->
            val result = post(url, "payload=1")
            assertTrue(
                result is NetResult.Error,
                "HTTP 500 must be a NetResult.Error, but got: $result"
            )
        }
    }

    @Test
    fun http404_isNotReportedAsSuccess() {
        withServer(404, "<html><body>Not Found</body></html>") { url ->
            val result = post(url, "payload=1")
            assertTrue(
                result is NetResult.Error,
                "HTTP 404 must be a NetResult.Error, but got: $result"
            )
        }
    }

    @Test
    fun http403_isNotReportedAsSuccess() {
        withServer(403, "<html><body>Forbidden</body></html>") { url ->
            val result = post(url, "payload=1")
            assertTrue(
                result is NetResult.Error,
                "HTTP 403 must be a NetResult.Error, but got: $result"
            )
        }
    }

    @Test
    fun http503_isNotReportedAsSuccess() {
        withServer(503, "Service Unavailable") { url ->
            val result = post(url, "payload=1")
            assertTrue(
                result is NetResult.Error,
                "HTTP 503 must be a NetResult.Error, but got: $result"
            )
        }
    }

    @Test
    fun successResponse_stillSucceeds() {
        withServer(200, "OK") { url ->
            val result = post(url, "payload=1")
            assertTrue(
                result is NetResult.Success,
                "HTTP 200 must remain a NetResult.Success, but got: $result"
            )
        }
    }
}
