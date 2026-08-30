package com.rsplwe.esurfing

import com.rsplwe.esurfing.auth.AuthState
import com.rsplwe.esurfing.diagnostics.DiagnosticStage
import com.rsplwe.esurfing.fixture.CanarySecrets
import com.rsplwe.esurfing.fixture.FixtureLoader
import com.rsplwe.esurfing.fixture.TestAuthFlow
import com.rsplwe.esurfing.fixture.AuthScenario
import com.rsplwe.esurfing.network.NetDiagnostics
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Lifecycle enforcement through the real authorization flow (MockWebServer +
 * production cipher): logged is only ever reached through a fully valid login
 * contract; every malformed/missing/rejected/decryption-failed input stays
 * unlogged; heartbeat failures degrade with a stage+reason event; re-auth never
 * reuses stale ticket/URL state; term is idempotent; shutdown stops requests.
 */
class AuthLifecycleTest {

    private val zsm = FixtureLoader.bytes("zsm/valid.zsm")

    @BeforeEach
    fun setUp() {
        NetDiagnostics.resetForTest()
    }

    private fun options(smsCode: String = CanarySecrets.CANARY_SMS_DEMO): Options =
        Options(CanarySecrets.CANARY_USER_DEMO, CanarySecrets.CANARY_PASSWORD_DEMO, smsCode)

    private fun ticketXml(ticket: String = "FIXTURE_TICKET_LIFECYCLE"): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <response><result>success</result>
            <ticket>$ticket</ticket>
        </response>
    """.trimIndent()

    private fun loginXml(
        keepUrl: String = "http://fixture.example.invalid/portal/keep",
        termUrl: String = "http://fixture.example.invalid/portal/term",
        retry: String = "30",
    ): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <response><result>success</result>
            <keep-url>$keepUrl</keep-url>
            <term-url>$termUrl</term-url>
            <keep-retry>$retry</keep-retry>
        </response>
    """.trimIndent()

    private fun reflect(client: Client, field: String): String {
        val f = Client::class.java.getDeclaredField(field)
        f.isAccessible = true
        return f.get(client) as String
    }

    private fun invokeHeartbeat(client: Client) {
        val m = Client::class.java.getDeclaredMethod("heartbeat", String::class.java)
        m.isAccessible = true
        m.invoke(client, States.ticket)
    }

    // --- happy path ---

    @Test
    fun fullAuth_marksLogged_withStableFields() {
        val result = TestAuthFlow.run(options(), zsm, ticketXml(), loginXml())
        assertTrue(States.isLogged, "valid contract must log in")
        assertEquals(AuthState.LOGGED, States.authState)
        assertEquals("FIXTURE_TICKET_LIFECYCLE", States.ticket)
        assertEquals(3, result.requestCount, "ZSM + ticket + login = 3 requests")
        assertEquals("30", reflect(result.client, "keepRetry"))
        assertEquals("http://fixture.example.invalid/portal/keep", reflect(result.client, "keepUrl"))
    }

    // --- login / ticket contract failures never reach LOGGED ---

    @Test
    fun loginMissingKeepUrl_neverLogs_andStops() {
        val session = TestAuthFlow.runSession(AuthScenario(options(), zsm, ticketXml(), FixtureLoader.text("xml/login-response-missing-keep-url.xml")))
        assertFalse(States.isLogged, "missing keep-url must never mark logged")
        assertFalse(States.isRunning, "a broken login contract stops the run loop")
        assertEquals(3, session.requestCount)
        session.close()
    }

    @Test
    fun loginMissingKeepRetry_neverLogs_butFieldStaysNumeric() {
        val result = TestAuthFlow.run(options(), zsm, ticketXml(), FixtureLoader.text("xml/login-response-missing-retry.xml"))
        assertFalse(States.isLogged)
        assertEquals("30", reflect(result.client, "keepRetry"), "invalid keep-retry must never propagate to the field")
    }

    @Test
    fun loginHttp500_neverLogs_andStops() {
        val session = TestAuthFlow.runSession(AuthScenario(options(), zsm, ticketXml(), loginXml(), loginStatus = 500))
        assertFalse(States.isLogged, "HTTP 500 login must never mark logged")
        assertFalse(States.isRunning)
        session.close()
    }

    @Test
    fun loginBusinessRejection_neverLogs() {
        val result = TestAuthFlow.run(options(), zsm, ticketXml(), FixtureLoader.text("xml/login-response-rejected.xml"))
        assertFalse(States.isLogged, "business rejection must never mark logged")
    }

    @Test
    fun ticketNonXml_neverLogs() {
        val result = TestAuthFlow.run(options(), zsm, FixtureLoader.text("xml/ticket-response-nonxml.txt"), loginXml())
        assertFalse(States.isLogged, "malformed ticket must never mark logged")
    }

    @Test
    fun ticketHttp500_neverLogs() {
        val result = TestAuthFlow.run(
            AuthScenario(options(), zsm, ticketXml(), loginXml(), ticketStatus = 500)
        )
        assertFalse(States.isLogged, "HTTP 500 ticket must never mark logged")
    }

    // --- heartbeat lifecycle ---

    @Test
    fun heartbeatSuccess_updatesIntervalAndStaysLogged() {
        val session = TestAuthFlow.runSession(AuthScenario(
            options(), zsm, ticketXml(), loginXml(keepUrl = "${TestAuthFlow.KEEP_URL_TOKEN}/keep"),
        ))
        session.enqueueEncrypted("""<?xml version="1.0" encoding="utf-8"?><response><result>success</result><interval>60</interval></response>""")
        invokeHeartbeat(session.client)
        assertEquals("60", reflect(session.client, "keepRetry"), "valid heartbeat rewrites the interval")
        assertEquals(AuthState.LOGGED, States.authState)
        assertTrue(States.isLogged)
        session.close()
    }

    @Test
    fun heartbeatHttpFailure_degrades_withStageEvent() {
        val session = TestAuthFlow.runSession(AuthScenario(
            options(), zsm, ticketXml(), loginXml(keepUrl = "${TestAuthFlow.KEEP_URL_TOKEN}/keep"),
        ))
        assertTrue(States.isLogged, "precondition: authenticated")
        session.enqueueRaw(MockResponse().setResponseCode(500).setBody("boom"))
        invokeHeartbeat(session.client)
        assertEquals(AuthState.DEGRADED, States.authState, "heartbeat HTTP failure must degrade")
        assertFalse(States.isLogged)
        val failure = NetDiagnostics.events().last()
        assertEquals(DiagnosticStage.HEARTBEAT, failure.stage, "heartbeat failure must carry the heartbeat stage")
        assertEquals(com.rsplwe.esurfing.diagnostics.ResultOutcome.FAILURE, failure.outcome)
        assertTrue(failure.errorCode != null, "reason must be classified")
        session.close()
    }

    @Test
    fun heartbeatInvalidInterval_degrades_withProtocolEvent() {
        val session = TestAuthFlow.runSession(AuthScenario(
            options(), zsm, ticketXml(), loginXml(keepUrl = "${TestAuthFlow.KEEP_URL_TOKEN}/keep"),
        ))
        session.enqueueEncrypted(FixtureLoader.text("xml/heartbeat-response-invalid-interval.xml"))
        invokeHeartbeat(session.client)
        assertEquals(AuthState.DEGRADED, States.authState, "invalid interval must degrade")
        assertFalse(States.isLogged)
        val failure = NetDiagnostics.events().last()
        assertEquals(DiagnosticStage.HEARTBEAT, failure.stage)
        assertEquals("protocol.missing_field", failure.errorCode)
        session.close()
    }

    // --- re-auth state hygiene ---

    @Test
    fun reauth_usesFreshTicket() {
        TestAuthFlow.run(options(), zsm, ticketXml("FIXTURE_TICKET_RUN_A"), loginXml())
        assertEquals("FIXTURE_TICKET_RUN_A", States.ticket)
        val second = TestAuthFlow.run(options(), zsm, ticketXml("FIXTURE_TICKET_RUN_B"), loginXml())
        assertEquals("FIXTURE_TICKET_RUN_B", States.ticket, "re-auth must fetch a fresh ticket, not reuse the old one")
        assertTrue(States.isLogged)
        assertEquals("http://fixture.example.invalid/portal/keep", reflect(second.client, "keepUrl"))
    }

    @Test
    fun reauth_afterFailedAttempt_doesNotReuseStaleState() {
        TestAuthFlow.run(
            options(), zsm, ticketXml("FIXTURE_TICKET_STALE"),
            loginXml(keepUrl = "http://stale.example.invalid/keep", retry = "abc"),
        )
        assertFalse(States.isLogged, "invalid keep-retry must not log in")
        val second = TestAuthFlow.run(options(), zsm, ticketXml("FIXTURE_TICKET_FRESH"), loginXml())
        assertEquals("FIXTURE_TICKET_FRESH", States.ticket, "stale ticket must not leak into the retry")
        assertEquals("http://fixture.example.invalid/portal/keep", reflect(second.client, "keepUrl"))
        assertTrue(States.isLogged)
    }

    // --- term idempotency ---

    @Test
    fun term_isIdempotent() {
        val session = TestAuthFlow.runSession(AuthScenario(
            options(), zsm, ticketXml(),
            loginXml(termUrl = "${TestAuthFlow.TERM_URL_TOKEN}/term"),
        ))
        val zero = session.requestCount
        assertEquals(3, zero)
        session.enqueueEncrypted("""<?xml version="1.0" encoding="utf-8"?><response><result>success</result></response>""")
        session.client.term()
        assertEquals(4, session.requestCount, "term must fire exactly one request")
        assertFalse(States.isLogged)
        assertEquals(AuthState.UNINITIALIZED, States.authState, "term releases the session state")
        session.client.term()
        assertEquals(4, session.requestCount, "a second term must be a no-op")
        session.close()
    }

    @Test
    fun termWithActiveSession_firesOnce() {
        val session = TestAuthFlow.runSession(AuthScenario(
            options(), zsm, ticketXml(),
            loginXml(termUrl = "${TestAuthFlow.TERM_URL_TOKEN}/term"),
        ))
        session.enqueueEncrypted("""<?xml version="1.0" encoding="utf-8"?><response><result>success</result></response>""")
        session.client.term()
        assertEquals(4, session.requestCount)
        session.client.term()
        assertEquals(4, session.requestCount)
        session.close()
    }

    // --- shutdown ---

    @Test
    fun shutdown_stopsHeartbeatRequests() {
        val session = TestAuthFlow.runSession(AuthScenario(options(), zsm, ticketXml(), loginXml()))
        States.isRunning = false
        invokeHeartbeat(session.client)
        assertEquals(3, session.requestCount, "no request may fire after shutdown")
        assertEquals(AuthState.SHUTDOWN, States.authState)
        session.close()
    }

    @Test
    fun uninitializedHeartbeat_isRefused_noRequest() {
        val server = MockWebServer()
        server.start()
        try {
            States.ticketUrl = server.url("/ticket").toString()
            States.authUrl = server.url("/auth").toString()
            States.isRunning = true
            States.authState = AuthState.UNINITIALIZED
            val client = Client(options())
            invokeHeartbeat(client)
            assertEquals(0, server.requestCount, "heartbeat before any session must not dispatch")
        } finally {
            server.shutdown()
            States.isRunning = true
        }
    }

    // --- SMS lifecycle ---

@Test
    fun smsStatusQueryFailure_abortsLogin() {
        val session = TestAuthFlow.runSession(AuthScenario(
            options(smsCode = ""), zsm, ticketXml(), loginXml(),
            smsStatusResponse = MockResponse().setResponseCode(500).setBody("boom"),
        ))
        assertFalse(States.isLogged, "SMS query failure must not log in blind")
        assertFalse(States.isRunning)
        assertEquals(1, session.requestCount, "only the SMS status query fires")
        session.close()
    }

    @Test
    fun smsNotRequired_logsInWithoutCode() {
        val session = TestAuthFlow.runSession(AuthScenario(
            options(smsCode = ""), zsm, ticketXml(), loginXml(),
            smsStatusResponse = MockResponse().setResponseCode(200).setBody(FixtureLoader.text("json/sms-status-not-required.json")),
        ))
        assertTrue(States.isLogged, "no-code verdict must proceed with a code-free login")
        assertEquals(4, session.requestCount, "SMS status + ZSM + ticket + login")
        session.close()
    }

    @Test
    fun smsRequiredButCodeEndpointFails_abortsLogin() {
        val session = TestAuthFlow.runSession(AuthScenario(
            options(smsCode = ""), zsm, ticketXml(), loginXml(),
            smsStatusResponse = MockResponse().setResponseCode(200).setBody(FixtureLoader.text("json/sms-status-requires.json")),
            smsCodeResponse = MockResponse().setResponseCode(500).setBody("boom"),
        ))
        assertFalse(States.isLogged)
        assertEquals(2, session.requestCount, "status + code queries only")
        session.close()
    }

    @Test
    fun smsRequiredButAlreadyVerified_skipsPromptAndLogsIn() {
        val session = TestAuthFlow.runSession(AuthScenario(
            options(smsCode = ""), zsm, ticketXml(), loginXml(),
            smsStatusResponse = MockResponse().setResponseCode(200).setBody(FixtureLoader.text("json/sms-status-requires.json")),
            smsCodeResponse = MockResponse().setResponseCode(200).setBody(FixtureLoader.text("json/sms-code-not-required.json")),
        ))
        assertTrue(States.isLogged, "already-verified (no code) must log in without prompting")
        assertEquals(5, session.requestCount, "status + code + ZSM + ticket + login")
        session.close()
    }
}