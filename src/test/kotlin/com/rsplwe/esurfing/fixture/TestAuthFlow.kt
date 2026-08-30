package com.rsplwe.esurfing.fixture

import com.rsplwe.esurfing.Client
import com.rsplwe.esurfing.Options
import com.rsplwe.esurfing.Session
import com.rsplwe.esurfing.States
import com.rsplwe.esurfing.auth.AuthState
import com.rsplwe.esurfing.cipher.CipherFactory
import com.rsplwe.esurfing.cipher.CipherInterface
import com.rsplwe.esurfing.cipher.CipherLookup
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.apache.log4j.Logger
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Drives Client.authorization() end-to-end against a MockWebServer using the
 * real production cipher. The ZSM embeds a known algo-id so Session.decrypt
 * round-trips with responses encrypted here through CipherFactory.
 *
 * Scenario responses are served in order: (optional SMS endpoints) then
 * 1. raw ZSM bytes (initSession -> Session.initialize)
 * 2. encrypted ticket XML (getTicket)
 * 3. encrypted login XML (login)
 *
 * [AuthScenario] supports the SMS endpoints (their responses are enqueued
 * first, before the ZSM, because the SMS queries run at the start of
 * authorization) and per-step HTTP status codes. The login XML may contain the
 * [KEEP_URL_TOKEN] / [TERM_URL_TOKEN] placeholders which are resolved to the
 * MockWebServer base URL so heartbeat/term calls can be driven deterministically.
 */
data class AuthFlowResult(
    val client: Client,
    val stdout: String,
    val logMessages: List<String>,
    val requestCount: Int,
)

data class AuthScenario(
    val options: Options,
    val zsm: ByteArray,
    val ticketXml: String,
    val loginXml: String,
    val ticketStatus: Int = 200,
    val loginStatus: Int = 200,
    val smsStatusResponse: MockResponse? = null,
    val smsCodeResponse: MockResponse? = null,
)

object AuthFlowUtil {
    val cipher: CipherInterface = run {
        val lookup = CipherFactory.getInstance(TestAuthFlow.FIXTURE_ALGO_ID)
        check(lookup is CipherLookup.Found) { "fixture Algo-Id must resolve" }
        lookup.cipher
    }
}

/**
 * An open authentication session. The MockWebServer stays alive until [close]
 * so tests can enqueue term/heartbeat responses after authorization and assert
 * on the served request count.
 */
class AuthFlowSession internal constructor(
    val client: Client,
    val stdout: String,
    val logMessages: List<String>,
    private val server: MockWebServer,
    private val cipher: CipherInterface,
) : AutoCloseable {

    val requestCount: Int get() = server.requestCount

    fun enqueueEncrypted(xml: String, status: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(status).setBody(cipher.encrypt(xml)))
    }

    fun enqueueRaw(response: MockResponse) {
        server.enqueue(response)
    }

    fun toResult(): AuthFlowResult = AuthFlowResult(client, stdout, logMessages, requestCount)

    override fun close() {
        server.shutdown()
        Session.free()
        States.isRunning = true
    }
}

object TestAuthFlow {

    const val FIXTURE_ALGO_ID: String = "CAFBCBAD-B6E7-4CAB-8A67-14D39F00CE1E"
    const val FIXTURE_SCHOOL_ID: String = "fixture-school-0001"
    const val KEEP_URL_TOKEN: String = "{{KEEP_URL}}"
    const val TERM_URL_TOKEN: String = "{{TERM_URL}}"

    fun run(options: Options, zsm: ByteArray, ticketXml: String, loginXml: String): AuthFlowResult =
        run(AuthScenario(options, zsm, ticketXml, loginXml))

    fun run(scenario: AuthScenario): AuthFlowResult =
        runSession(scenario).use { it.toResult() }

    fun runSession(scenario: AuthScenario): AuthFlowSession {
        require(
            scenario.options.smsCode.isBlank() ||
                (scenario.smsStatusResponse == null && scenario.smsCodeResponse == null)
        ) { "SMS endpoint fixtures require a blank options.smsCode so the queries actually fire" }

        States.extraCfgUrl = HashMap()
        States.schoolId = FIXTURE_SCHOOL_ID
        States.ticket = ""
        States.area = ""
        States.domain = ""
        States.userIp = ""
        States.acIp = ""
        States.isLogged = false
        States.authState = AuthState.UNINITIALIZED
        States.isRunning = true
        Session.free()

        val server = MockWebServer()
        server.start()
        val base = server.url("/").toString()
        scenario.smsStatusResponse?.let { server.enqueue(it) }
        scenario.smsCodeResponse?.let { server.enqueue(it) }
        val zsmBuffer = Buffer().apply { write(scenario.zsm) }
        server.enqueue(MockResponse().setResponseCode(200).setBody(zsmBuffer))
        server.enqueue(MockResponse().setResponseCode(scenario.ticketStatus).setBody(AuthFlowUtil.cipher.encrypt(scenario.ticketXml)))
        val loginBody = scenario.loginXml
            .replace(KEEP_URL_TOKEN, "$base" + "keep")
            .replace(TERM_URL_TOKEN, "$base" + "term")
        server.enqueue(MockResponse().setResponseCode(scenario.loginStatus).setBody(AuthFlowUtil.cipher.encrypt(loginBody)))

        States.ticketUrl = server.url("/ticket").toString()
        States.authUrl = server.url("/auth").toString()
        if (scenario.smsStatusResponse != null) {
            States.extraCfgUrl["QueryVerificateCodeStatus"] = server.url("/sms-status").toString()
        }
        if (scenario.smsCodeResponse != null) {
            States.extraCfgUrl["QueryAuthCode"] = server.url("/sms-code").toString()
        }

        val client = Client(scenario.options)
        val appender = TestLogAppender()
        Logger.getRootLogger().addAppender(appender)
        val stdoutBuffer = ByteArrayOutputStream()
        val originalOut = System.out
        System.setOut(PrintStream(stdoutBuffer))
        try {
            val authorization = Client::class.java.getDeclaredMethod("authorization")
            authorization.isAccessible = true
            authorization.invoke(client)
        } finally {
            System.setOut(originalOut)
            Logger.getRootLogger().removeAppender(appender)
        }
        return AuthFlowSession(client, stdoutBuffer.toString(), appender.messages.toList(), server, AuthFlowUtil.cipher)
    }
}