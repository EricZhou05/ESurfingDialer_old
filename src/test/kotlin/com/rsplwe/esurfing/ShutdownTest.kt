package com.rsplwe.esurfing

import com.rsplwe.esurfing.auth.AuthState
import com.rsplwe.esurfing.diagnostics.EvidenceManifest
import com.rsplwe.esurfing.diagnostics.RunMode
import com.rsplwe.esurfing.fixture.AuthScenario
import com.rsplwe.esurfing.fixture.CanarySecrets
import com.rsplwe.esurfing.fixture.FixtureLoader
import com.rsplwe.esurfing.fixture.TestAuthFlow
import com.rsplwe.esurfing.fixture.TestLogAppender
import com.rsplwe.esurfing.network.NetDiagnostics
import java.nio.file.Files
import java.nio.file.Path
import org.apache.log4j.Logger
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reliable shutdown contract (Todo 6 SHUTDOWN state + Todo 7 flush wiring):
 *  - term success AND term failure both complete the event/manifest flush
 *  - after shutdown the state machine sits in SHUTDOWN and no request fires
 *  - NORMAL mode never creates sensitive/; SENSITIVE mode records it in manifest
 */
class ShutdownTest {

    @TempDir
    lateinit var tmp: Path

    private val zsm = FixtureLoader.bytes("zsm/valid.zsm")

    @BeforeEach
    fun setUp() {
        NetDiagnostics.resetForTest()
    }

    private fun options(smsCode: String = CanarySecrets.CANARY_SMS_DEMO): Options =
        Options(CanarySecrets.CANARY_USER_DEMO, CanarySecrets.CANARY_PASSWORD_DEMO, smsCode)

    private fun ticketXml(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <response><result>success</result>
            <ticket>FIXTURE_TICKET_SHUTDOWN</ticket>
        </response>
    """.trimIndent()

    private fun loginXml(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <response><result>success</result>
            <keep-url>${TestAuthFlow.KEEP_URL_TOKEN}/keep</keep-url>
            <term-url>${TestAuthFlow.TERM_URL_TOKEN}/term</term-url>
            <keep-retry>30</keep-retry>
        </response>
    """.trimIndent()

    private fun invokeHeartbeat(client: Client) {
        val m = Client::class.java.getDeclaredMethod("heartbeat", String::class.java)
        m.isAccessible = true
        m.invoke(client, States.ticket)
    }

    private fun runner(mode: RunMode = RunMode.NORMAL): DiagnosticsRunner =
        DiagnosticsRunner.open(tmp, mode = mode)

    private fun manifest(r: DiagnosticsRunner): EvidenceManifest =
        EvidenceManifest.fromJson(Files.readString(r.runDir.resolve("manifest.json")))

    @Test
    fun termSuccess_flushesEventsAndFinalizesManifest() {
        val session = TestAuthFlow.runSession(AuthScenario(options(), zsm, ticketXml(), loginXml()))
        val r = runner()
        val handler = ShutdownHandler(session.client, r)

        session.enqueueEncrypted("""<?xml version="1.0" encoding="utf-8"?><response><result>success</result></response>""")
        handler.shutdown()

        assertEquals(AuthState.SHUTDOWN, States.authState, "shutdown must drive the state machine to SHUTDOWN")
        assertFalse(States.isRunning, "shutdown must stop the run loop")
        assertEquals(4, session.requestCount, "login(3 requests) + term(1) must fire exactly once")

        val m = manifest(r)
        assertTrue(
            m.entries.any { it.kind == "EVENT_JSONL" },
            "events must be flushed into the run; kinds=${m.entries.map { it.kind }}",
        )
        session.close()
    }

    @Test
    fun termFailure_unreachableUrl_stillFlushesManifestAndStops() {
        val session = TestAuthFlow.runSession(AuthScenario(options(), zsm, ticketXml(), loginXml()))
        val r = runner()
        val handler = ShutdownHandler(session.client, r)
        val appender = TestLogAppender()
        Logger.getRootLogger().addAppender(appender)

        session.client.termUrl = "http://127.0.0.1:1/term" // unroutable -> term transport failure

        try {
            handler.shutdown()
        } finally {
            Logger.getRootLogger().removeAppender(appender)
        }

        assertEquals(AuthState.SHUTDOWN, States.authState, "shutdown must still reach SHUTDOWN after a term failure")
        assertFalse(States.isRunning)

        val m = manifest(r)
        assertTrue(
            m.entries.any { it.kind == "EVENT_JSONL" },
            "flush must complete even when term fails; kinds=${m.entries.map { it.kind }}",
        )
        assertTrue(
            appender.messages.any { it.contains("Term failed") },
            "the term failure must be captured and logged; logs=${appender.messages}",
        )

        val before = session.requestCount
        invokeHeartbeat(session.client)
        assertEquals(before, session.requestCount, "no heartbeat request may fire after shutdown")
        session.close()
    }

    @Test
    fun shutdown_normalMode_writesNoSensitiveContent() {
        val session = TestAuthFlow.runSession(AuthScenario(options(), zsm, ticketXml(), loginXml()))
        val r = runner(mode = RunMode.NORMAL)
        ShutdownHandler(session.client, r).shutdown()

        assertFalse(Files.exists(r.runDir.resolve("sensitive")), "NORMAL mode must never create sensitive/")
        session.close()
    }

    @Test
    fun shutdown_sensitiveMode_recordsSensitiveRunInManifest() {
        val session = TestAuthFlow.runSession(AuthScenario(options(), zsm, ticketXml(), loginXml()))
        val r = runner(mode = RunMode.SENSITIVE)
        ShutdownHandler(session.client, r).shutdown()

        assertTrue(Files.isDirectory(r.runDir.resolve("sensitive")), "SENSITIVE mode creates sensitive/")
        assertEquals("sensitive", manifest(r).mode, "manifest must record the sensitive run mode")
        session.close()
    }

    @Test
    fun authorizationAfterShutdown_dispatchesNothing() {
        val session = TestAuthFlow.runSession(AuthScenario(options(), zsm, ticketXml(), loginXml()))
        val r = runner()
        val handler = ShutdownHandler(session.client, r)
        handler.shutdown()

        val before = session.requestCount
        val authMethod = Client::class.java.getDeclaredMethod("authorization")
        authMethod.isAccessible = true
        authMethod.invoke(session.client)
        assertEquals(before, session.requestCount, "authorization after shutdown must not dispatch")
        session.close()
    }
}