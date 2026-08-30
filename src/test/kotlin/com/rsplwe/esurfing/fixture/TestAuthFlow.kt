package com.rsplwe.esurfing.fixture

import com.rsplwe.esurfing.Client
import com.rsplwe.esurfing.Options
import com.rsplwe.esurfing.Session
import com.rsplwe.esurfing.States
import com.rsplwe.esurfing.cipher.CipherFactory
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
 * Responses served in order:
 *   1. raw ZSM bytes        (initSession -> Session.initialize)
 *   2. encrypted ticket XML (getTicket)
 *   3. encrypted login XML  (login)
 */
data class AuthFlowResult(
    val client: Client,
    val stdout: String,
    val logMessages: List<String>,
)

object TestAuthFlow {

    const val FIXTURE_ALGO_ID: String = "CAFBCBAD-B6E7-4CAB-8A67-14D39F00CE1E"

    fun run(
        options: Options,
        zsm: ByteArray,
        ticketXml: String,
        loginXml: String,
    ): AuthFlowResult {
        val cipher = CipherFactory.getInstance(FIXTURE_ALGO_ID)
        val server = MockWebServer()
        val zsmBuffer = Buffer().apply { write(zsm) }
        server.enqueue(MockResponse().setBody(zsmBuffer))
        server.enqueue(MockResponse().setBody(cipher.encrypt(ticketXml)))
        server.enqueue(MockResponse().setBody(cipher.encrypt(loginXml)))
        server.start()
        try {
            States.ticketUrl = server.url("/ticket").toString()
            States.authUrl = server.url("/auth").toString()

            val client = Client(options)
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
            return AuthFlowResult(client, stdoutBuffer.toString(), appender.messages.toList())
        } finally {
            server.shutdown()
            Session.free()
        }
    }
}
