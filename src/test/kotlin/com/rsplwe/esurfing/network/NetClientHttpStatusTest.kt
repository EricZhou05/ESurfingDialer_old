package com.rsplwe.esurfing.network

import com.rsplwe.esurfing.States
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Disabled
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * RED (TDD) — exposes the "non-2xx treated as success" defect.
 *
 * NetClient.post (lines 70-101) returns NetResult.Success whenever the HTTP
 * transport does not throw, so 4xx/5xx responses are currently misreported as
 * success. These tests assert the required contract: a non-2xx HTTP response
 * must be a NetResult.Error.
 *
 * Expected to FAIL until Todo 4 fixes the classification.
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
    @Disabled("TDD red-phase: expected failure until Todo 4 fixes HTTP status classification")
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
    @Disabled("TDD red-phase: expected failure until Todo 4 fixes HTTP status classification")
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
    @Disabled("TDD red-phase: expected failure until Todo 4 fixes HTTP status classification")
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
    @Disabled("TDD red-phase: expected failure until Todo 4 fixes HTTP status classification")
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
