package com.rsplwe.esurfing

import com.rsplwe.esurfing.fixture.CanarySecrets
import com.rsplwe.esurfing.fixture.FixtureLoader
import com.rsplwe.esurfing.fixture.TestAuthFlow
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * RED (TDD) — exposes the "empty field propagation" and "keepRetry.toLong()
 * crash" defects.
 *
 * Client.login (lines 171-173) writes `?: ""` when keep-url/term-url/keep-retry
 * are missing, propagating empty strings instead of a typed failure, and
 * Client.run (line 34) calls `keepRetry.toLong()` unvalidated — crashing with
 * NumberFormatException when keep-retry is blank or non-numeric.
 *
 * Expected to FAIL until Todo 6 enforces the protocol contract.
 */
class EmptyFieldPropagationTest {

    private val zsm = FixtureLoader.bytes("zsm/valid.zsm")

    private fun ticketXml(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <response><result>success</result>
            <ticket>${CanarySecrets.CANARY_TICKET_DEMO}</ticket>
        </response>
    """.trimIndent()

    private fun loginXml(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <response><result>success</result>
            <keep-url>http://fixture.example.invalid/portal/keep</keep-url>
            <term-url>http://fixture.example.invalid/portal/term</term-url>
            <keep-retry>30</keep-retry>
        </response>
    """.trimIndent()

    private fun options(): Options = Options(
        loginUser = CanarySecrets.CANARY_USER_DEMO,
        loginPassword = CanarySecrets.CANARY_PASSWORD_DEMO,
        smsCode = CanarySecrets.CANARY_SMS_DEMO,
    )

    private fun keepRetry(client: Client): String {
        val field = Client::class.java.getDeclaredField("keepRetry")
        field.isAccessible = true
        return field.get(client) as String
    }

    private fun keepUrl(client: Client): String {
        val field = Client::class.java.getDeclaredField("keepUrl")
        field.isAccessible = true
        return field.get(client) as String
    }

    @Test
    fun keepRetry_toLong_mustNotThrowWhenMissing() {
        val result = TestAuthFlow.run(
            options(), zsm, ticketXml(),
            FixtureLoader.text("xml/login-response-missing-retry.xml"),
        )
        val parsed = runCatching { keepRetry(result.client).toLong() }.getOrNull()
        assertNotNull(
            parsed,
            "keepRetry must be a valid positive number even when the field is missing, " +
                "but got: '${keepRetry(result.client)}' (toLong() threw or returned null)"
        )
    }

    @Test
    fun keepRetry_mustNotBeBlankWhenMissing() {
        val result = TestAuthFlow.run(
            options(), zsm, ticketXml(),
            FixtureLoader.text("xml/login-response-missing-retry.xml"),
        )
        val retry = keepRetry(result.client)
        assertTrue(
            retry.isNotBlank(),
            "missing keep-retry must not propagate an empty string, but got: '$retry'"
        )
    }

    @Test
    fun keepRetry_mustNotBeBlankWhenInvalid() {
        val result = TestAuthFlow.run(
            options(), zsm, ticketXml(),
            FixtureLoader.text("xml/login-response-invalid-retry.xml"),
        )
        val retry = keepRetry(result.client)
        assertTrue(
            retry.toLongOrNull() != null,
            "invalid keep-retry ('abc') must not propagate as-is, but got: '$retry'"
        )
    }

    @Test
    fun keepUrl_mustNotBeEmptyWhenPresent() {
        val result = TestAuthFlow.run(options(), zsm, ticketXml(), loginXml())
        val url = keepUrl(result.client)
        assertTrue(
            url.isNotBlank(),
            "present keep-url must be parsed, but got: '$url'"
        )
    }
}
