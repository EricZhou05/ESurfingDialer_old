package com.rsplwe.esurfing

import com.rsplwe.esurfing.auth.AuthEvent
import com.rsplwe.esurfing.auth.AuthRequestKind
import com.rsplwe.esurfing.auth.AuthState
import com.rsplwe.esurfing.auth.AuthStateMachine
import com.rsplwe.esurfing.diagnostics.DiagnosticStage
import com.rsplwe.esurfing.protocol.AuthSmsOutcome
import com.rsplwe.esurfing.protocol.LoginParseResult
import com.rsplwe.esurfing.protocol.ProtocolLimits
import com.rsplwe.esurfing.protocol.TicketParseResult
import com.rsplwe.esurfing.utils.ConnectivityStatus.REQUIRE_AUTHORIZATION
import com.rsplwe.esurfing.utils.ConnectivityStatus.REQUEST_ERROR
import com.rsplwe.esurfing.utils.ConnectivityStatus.SUCCESS
import com.rsplwe.esurfing.utils.detectConfig
import org.apache.log4j.Logger
import java.lang.Thread.sleep

/**
 * Orchestrates the authentication lifecycle. The protocol steps (ZSM/ticket/
 * login/heartbeat/term exchanges and SMS handling) live in [ClientAuthSteps];
 * this class owns the run loop, the state machine and the public `term()`
 * contract.
 */
class Client(private val options: Options) {

    private val logger: Logger = Logger.getLogger(Client::class.java)
    private val steps = ClientAuthSteps(this, options)

    // Only ever assigned from a validated login/heartbeat parse (1..3600) or
    // the protocol default, so the `.toLong()` in run() cannot throw.
    internal var keepUrl = ""
    internal var termUrl = ""
    internal var keepRetry = ProtocolLimits.DEFAULT_KEEP_RETRY_SECONDS.toString()
    internal var terminated = false

    @Volatile
    var tick: Long = 0

    fun run() {
        while (States.isRunning) {
            when (detectConfig()) {
                SUCCESS -> tickIfDue()
                REQUIRE_AUTHORIZATION -> {
                    States.isLogged = false
                    authorization()
                }
                REQUEST_ERROR -> {
                    logger.error("Request Error")
                    sleep(5000)
                }
            }
        }
    }

    private fun tickIfDue() {
        if (Session.isInitialized() && States.isLogged && isAllowed(AuthRequestKind.HEARTBEAT)) {
            val intervalMs = keepRetry.toLong() * 1000
            if ((System.currentTimeMillis() - tick) >= intervalMs) {
                logger.info("Send Keep Packet")
                heartbeat(States.ticket)
                logger.info("Next Retry: $keepRetry")
                tick = System.currentTimeMillis()
            }
        } else {
            logger.info("The network has been connected.")
        }
        sleep(1000)
    }

    private fun authorization() {
        if (!States.isRunning) {
            logger.warn("Authorization refused: the client is shutting down.")
            return
        }
        if (!apply(AuthEvent.AUTH_ATTEMPT_STARTED)) {
            logger.warn("Authorization refused in state ${States.authState}.")
            return
        }
        clearAttemptState()

        val smsOutcome = steps.resolveSmsOutcome()
        if (smsOutcome is AuthSmsOutcome.QueryFailed) {
            logger.error("SMS verification query failed: ${smsOutcome.safeSummary}")
            abortAuthorization()
            return
        }
        val smsCode = (smsOutcome as? AuthSmsOutcome.CodeProvided)?.code.orEmpty()

        States.refreshStates()
        States.ticket = ""

        if (steps.initSession() is SessionStep.Failed) {
            logger.error("Session initialization failed.")
            abortAuthorization()
            return
        }
        if (!apply(AuthEvent.SESSION_INITIALIZED)) {
            logger.error("Session initialization refused in state ${States.authState}.")
            abortAuthorization()
            return
        }

        logger.info("Client IP: ${States.userIp}")
        logger.info("AC IP: ${States.acIp}")

        when (val ticketRaw = steps.fetchTicket()) {
            is TicketParseResult.Success -> {
                States.ticket = ticketRaw.ticket
                when (val login = steps.performLogin(smsCode)) {
                    is LoginParseResult.Success -> {
                        steps.applyLoginUrls(login)
                        tick = System.currentTimeMillis()
                        if (!apply(AuthEvent.LOGIN_SUCCESS)) {
                            logger.error("Login success refused in state ${States.authState}.")
                            abortAuthorization()
                            return
                        }
                        States.isLogged = true
                        logger.info("The login has been authorized.")
                    }
                    is LoginParseResult.Failure -> {
                        steps.recordContractFailure(DiagnosticStage.LOGIN, login.reason, login.safeSummary)
                        logger.error("Login failed: ${login.safeSummary}")
                        abortAuthorization()
                    }
                }
            }
            is TicketParseResult.Failure -> {
                steps.recordContractFailure(DiagnosticStage.TICKET, ticketRaw.reason, ticketRaw.safeSummary)
                logger.error("Ticket failed: ${ticketRaw.safeSummary}")
                abortAuthorization()
            }
        }
    }

    fun term() {
        steps.performTerm()
    }

    fun heartbeat(ticket: String) {
        steps.heartbeat(ticket)
    }

    internal fun apply(event: AuthEvent): Boolean {
        val next = AuthStateMachine.next(States.authState, event) ?: return false
        States.authState = next
        return true
    }

    internal fun isAllowed(kind: AuthRequestKind): Boolean = AuthStateMachine.canIssue(States.authState, kind)

    internal fun transitionTo(state: AuthState) {
        States.authState = state
    }

    internal fun clearAttemptState() {
        States.isLogged = false
        States.ticket = ""
        keepUrl = ""
        termUrl = ""
        keepRetry = ProtocolLimits.DEFAULT_KEEP_RETRY_SECONDS.toString()
        terminated = false
        Session.free()
    }

    internal fun abortAuthorization() {
        Session.free()
        States.isLogged = false
        States.isRunning = false
    }
}