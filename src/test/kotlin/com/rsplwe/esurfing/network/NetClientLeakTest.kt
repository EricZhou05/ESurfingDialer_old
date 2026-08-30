package com.rsplwe.esurfing.network

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Repeatability / resource-hygiene: every classified outcome must close its
 * response body so the OkHttp connection pool reuses the same connection.
 * Leaking bodies grow the pool by ~one connection per request; a clean
 * implementation grows it by at most the first connection.
 */
class NetClientLeakTest {

    @Test
    fun repeatedNon2xxResponses_doNotGrowConnectionPool() {
        val server = MockWebServer()
        server.start()
        val url = server.url("/auth").toString()
        try {
            val before = apiClient.connectionPool.connectionCount()
            repeat(30) { i ->
                server.enqueue(MockResponse().setResponseCode(500).setBody("boom $i"))
                val result = post(url, "payload=$i")
                assertTrue(result is NetResult.Error, "iteration $i: expected Error, got: $result")
            }
            assertEquals(30, server.requestCount, "all 30 requests must reach the server")
            val after = apiClient.connectionPool.connectionCount()
            assertTrue(
                after - before <= 2,
                "leaked response bodies: pool grew before=$before after=$after (expected <= 2)"
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun repeatedSuccessResponses_doNotGrowConnectionPool() {
        val server = MockWebServer()
        server.start()
        val url = server.url("/auth").toString()
        try {
            val before = apiClient.connectionPool.connectionCount()
            repeat(30) { i ->
                server.enqueue(MockResponse().setResponseCode(200).setBody("ok $i"))
                val result = post(url, "payload=$i")
                assertTrue(result is NetResult.Success, "iteration $i: expected Success, got: $result")
                result.data.close()
            }
            val after = apiClient.connectionPool.connectionCount()
            assertTrue(
                after - before <= 2,
                "leaked success bodies: pool grew before=$before after=$after (expected <= 2)"
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun repeatedRedirectLimitResponses_doNotGrowConnectionPool() {
        val server = MockWebServer()
        server.start()
        val url = server.url("/auth").toString()
        try {
            val before = apiClient.connectionPool.connectionCount()
            repeat(20) { i ->
                server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", url))
                server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", url))
                server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", url))
                server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", url))
                server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", url))
                server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", url))
                val result = post(url, "payload=$i")
                assertTrue(result is NetResult.Error, "iteration $i: expected Error, got: $result")
            }
            val after = apiClient.connectionPool.connectionCount()
            assertTrue(
                after - before <= 2,
                "leaked redirect responses: pool grew before=$before after=$after (expected <= 2)"
            )
        } finally {
            server.shutdown()
        }
    }
}