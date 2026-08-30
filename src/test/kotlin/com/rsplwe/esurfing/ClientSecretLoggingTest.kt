package com.rsplwe.esurfing

import com.rsplwe.esurfing.fixture.CanarySecrets
import com.rsplwe.esurfing.fixture.FixtureLoader
import com.rsplwe.esurfing.fixture.TestAuthFlow
import org.junit.jupiter.api.Disabled
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * RED (TDD) — exposes the "SMS code / ticket logged to console" defects.
 *
 * Client.authorization prints the SMS code via println (Client.kt:64) and logs
 * the ticket (Client.kt:80). Both are session secrets and must never appear in
 * stdout or ordinary logs.
 *
 * Expected to FAIL until Todo 6 fixes the logging paths.
 */
class ClientSecretLoggingTest {

    private val ticketXml = """
        <?xml version="1.0" encoding="utf-8"?>
        <response>
            <result>success</result>
            <ticket>${CanarySecrets.CANARY_TICKET_DEMO}</ticket>
        </response>
    """.trimIndent()

    private val loginXml = """
        <?xml version="1.0" encoding="utf-8"?>
        <response>
            <result>success</result>
            <keep-url>http://fixture.example.invalid/portal/keep</keep-url>
            <term-url>http://fixture.example.invalid/portal/term</term-url>
            <keep-retry>30</keep-retry>
        </response>
    """.trimIndent()

    private val zsm = FixtureLoader.bytes("zsm/valid.zsm")

    @Test
    @Disabled("TDD red-phase: expected failure until Todo 6 stops printing the SMS code")
    fun authorization_mustNotPrintSmsCodeToStdout() {
        val options = Options(
            loginUser = CanarySecrets.CANARY_USER_DEMO,
            loginPassword = CanarySecrets.CANARY_PASSWORD_DEMO,
            smsCode = CanarySecrets.CANARY_SMS_DEMO, // non-blank, skips checkSMSVerify
        )
        val result = TestAuthFlow.run(options, zsm, ticketXml, loginXml)
        assertFalse(
            result.stdout.contains(CanarySecrets.CANARY_SMS_DEMO),
            "stdout must not contain the SMS code, but got: ${result.stdout.lines().filter { it.isNotBlank() }}"
        )
    }

    @Test
    @Disabled("TDD red-phase: expected failure until Todo 6 stops logging the ticket")
    fun authorization_mustNotLogTicket() {
        val options = Options(
            loginUser = CanarySecrets.CANARY_USER_DEMO,
            loginPassword = CanarySecrets.CANARY_PASSWORD_DEMO,
            smsCode = CanarySecrets.CANARY_SMS_DEMO,
        )
        val result = TestAuthFlow.run(options, zsm, ticketXml, loginXml)
        assertFalse(
            result.logMessages.any { it.contains(CanarySecrets.CANARY_TICKET_DEMO) },
            "logs must not contain the ticket, but found it in: ${result.logMessages.filter { it.contains(CanarySecrets.CANARY_TICKET_DEMO) }}"
        )
    }

    @Test
    fun authorization_mustNotLogPassword() {
        val options = Options(
            loginUser = CanarySecrets.CANARY_USER_DEMO,
            loginPassword = CanarySecrets.CANARY_PASSWORD_DEMO,
            smsCode = CanarySecrets.CANARY_SMS_DEMO,
        )
        val result = TestAuthFlow.run(options, zsm, ticketXml, loginXml)
        assertFalse(
            result.stdout.contains(CanarySecrets.CANARY_PASSWORD_DEMO) ||
                result.logMessages.any { it.contains(CanarySecrets.CANARY_PASSWORD_DEMO) },
            "stdout/logs must not contain the password"
        )
    }
}
