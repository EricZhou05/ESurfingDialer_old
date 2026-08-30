package com.rsplwe.esurfing

import com.rsplwe.esurfing.auth.AuthEvent
import com.rsplwe.esurfing.auth.AuthRequestKind
import com.rsplwe.esurfing.auth.AuthState
import com.rsplwe.esurfing.auth.AuthStateMachine
import com.rsplwe.esurfing.cipher.CipherOutcome
import com.rsplwe.esurfing.diagnostics.DiagnosticResult
import com.rsplwe.esurfing.diagnostics.DiagnosticStage
import com.rsplwe.esurfing.diagnostics.ProtocolResult
import com.rsplwe.esurfing.network.NetDiagnostics
import com.rsplwe.esurfing.network.NetResult
import com.rsplwe.esurfing.network.post
import com.rsplwe.esurfing.network.safeUrlOf
import com.rsplwe.esurfing.protocol.AuthPayloads
import com.rsplwe.esurfing.protocol.AuthResponseParser
import com.rsplwe.esurfing.protocol.AuthSmsOutcome
import com.rsplwe.esurfing.protocol.ContractFailure
import com.rsplwe.esurfing.protocol.HeartbeatParseResult
import com.rsplwe.esurfing.protocol.LoginParseResult
import com.rsplwe.esurfing.protocol.SmsVerifyOutcome
import com.rsplwe.esurfing.protocol.TermParseResult
import com.rsplwe.esurfing.protocol.TicketParseResult
import com.rsplwe.esurfing.protocol.asDiagnosticResult
import com.rsplwe.esurfing.utils.checkVerifyCodeStatus
import com.rsplwe.esurfing.utils.getVerifyCode
import org.apache.log4j.Logger

/** Outcome of a single lifecycle step; failures keep a non-secret label. */
internal sealed interface SessionStep {
    data object Ok : SessionStep
    data object Failed : SessionStep
}

/**
 * Executes the individual authentication protocol steps for [Client]: the
 * ZSM/ticket/login/heartbeat/term exchanges (post → decrypt → typed parse) and
 * the SMS verification flow. Orchestration and state transitions stay in
 * [Client].
 */
internal class ClientAuthSteps(
    private val client: Client,
    private val options: Options,
) {

    private val logger: Logger = Logger.getLogger(ClientAuthSteps::class.java)

    fun initSession(): SessionStep {
        return NetDiagnostics.withStage(DiagnosticStage.SESSION_ZSM) {
            when (val result = post(States.ticketUrl, States.algoId)) {
                is NetResult.Success -> {
                    Session.initialize(result.data.bytes())
                    if (Session.isInitialized()) {
                        SessionStep.Ok
                    } else {
                        Session.lastProtocolResult()?.let {
                            NetDiagnostics.record(DiagnosticStage.SESSION_ZSM, it)
                        }
                        SessionStep.Failed
                    }
                }
                is NetResult.Error -> SessionStep.Failed
            }
        }
    }

    fun fetchTicket(): TicketParseResult {
        val payload = AuthPayloads.ticket()
        return NetDiagnostics.withStage(DiagnosticStage.TICKET) {
            when (val result = post(States.ticketUrl, Session.encrypt(payload))) {
                is NetResult.Success -> when (val out = Session.tryDecrypt(result.data.string())) {
                    is CipherOutcome.Success -> AuthResponseParser.parseTicket(out.plain)
                    is CipherOutcome.Failure -> TicketParseResult.Failure(ContractFailure.DECRYPT, out.safeSummary)
                }
                is NetResult.Error -> TicketParseResult.Failure(ContractFailure.REQUEST_FAILED, result.exception)
            }
        }
    }

    fun performLogin(smsCode: String): LoginParseResult {
        if (!client.isAllowed(AuthRequestKind.LOGIN)) {
            return LoginParseResult.Failure(ContractFailure.REQUEST_FAILED, "login_refused_state_${States.authState}")
        }
        val payload = AuthPayloads.login(
            ticket = States.ticket,
            smsCode = smsCode,
            user = options.loginUser,
            password = options.loginPassword,
        )
        return NetDiagnostics.withStage(DiagnosticStage.LOGIN) {
            when (val result = post(States.authUrl, Session.encrypt(payload))) {
                is NetResult.Success -> when (val out = Session.tryDecrypt(result.data.string())) {
                    is CipherOutcome.Success -> AuthResponseParser.parseLogin(out.plain)
                    is CipherOutcome.Failure -> LoginParseResult.Failure(ContractFailure.DECRYPT, out.safeSummary)
                }
                is NetResult.Error -> LoginParseResult.Failure(ContractFailure.REQUEST_FAILED, result.exception)
            }
        }
    }

    fun heartbeat(ticket: String) {
        if (!States.isRunning) {
            client.transitionTo(AuthState.SHUTDOWN)
            logger.warn("Heartbeat refused: the client is shutting down.")
            return
        }
        if (!client.isAllowed(AuthRequestKind.HEARTBEAT)) {
            logger.warn("Heartbeat refused in state ${States.authState}.")
            return
        }
        val payload = AuthPayloads.heartbeat(ticket)
        NetDiagnostics.withStage(DiagnosticStage.HEARTBEAT) {
            when (val result = post(client.keepUrl, Session.encrypt(payload))) {
                is NetResult.Success -> when (val out = Session.tryDecrypt(result.data.string())) {
                    is CipherOutcome.Success -> when (val parsed = AuthResponseParser.parseHeartbeat(out.plain)) {
                        is HeartbeatParseResult.Success -> {
                            client.keepRetry = parsed.intervalSeconds.toString()
                            client.apply(AuthEvent.HEARTBEAT_SUCCESS)
                        }
                        is HeartbeatParseResult.Failure -> {
                            recordContractFailure(DiagnosticStage.HEARTBEAT, parsed.reason, parsed.safeSummary)
                            degradeHeartbeat(parsed.safeSummary)
                        }
                    }
                    is CipherOutcome.Failure -> {
                        NetDiagnostics.record(DiagnosticStage.HEARTBEAT, ProtocolResult.Failure(out.errorCode, out.safeSummary))
                        degradeHeartbeat(out.safeSummary)
                    }
                }
                is NetResult.Error -> degradeHeartbeat(result.exception)
            }
        }
    }

    fun performTerm() {
        if (client.terminated) {
            return
        }
        if (!client.isAllowed(AuthRequestKind.TERM) || !Session.isInitialized()) {
            logger.info("No active session to terminate.")
            client.terminated = true
            return
        }
        client.terminated = true
        val payload = AuthPayloads.term(States.ticket)
        NetDiagnostics.withStage(DiagnosticStage.TERM) {
            when (val result = post(client.termUrl, Session.encrypt(payload))) {
                is NetResult.Success -> when (val out = Session.tryDecrypt(result.data.string())) {
                    is CipherOutcome.Success -> when (val parsed = AuthResponseParser.parseTerm(out.plain)) {
                        is TermParseResult.Success -> finishTerm()
                        is TermParseResult.Failure -> {
                            recordContractFailure(DiagnosticStage.TERM, parsed.reason, parsed.safeSummary)
                            logger.error("Term rejected: ${parsed.safeSummary}")
                            finishTerm()
                        }
                    }
                    is CipherOutcome.Failure -> {
                        NetDiagnostics.record(DiagnosticStage.TERM, ProtocolResult.Failure(out.errorCode, out.safeSummary))
                        logger.error("Term failed: ${out.safeSummary}")
                        finishTerm()
                    }
                }
                is NetResult.Error -> {
                    logger.error("Term failed: ${result.exception}")
                    finishTerm()
                }
            }
        }
    }

    fun resolveSmsOutcome(): AuthSmsOutcome {
        if (options.smsCode.isNotBlank()) {
            return AuthSmsOutcome.CodeProvided(options.smsCode)
        }
        return when (val status = checkVerifyCodeStatus(options.loginUser)) {
            is SmsVerifyOutcome.RequiresVerification -> {
                logger.info("This login requires a SMS verification code.")
                when (val code = getVerifyCode(options.loginUser)) {
                    is SmsVerifyOutcome.RequiresVerification -> promptSmsCode()
                    is SmsVerifyOutcome.NotRequired -> AuthSmsOutcome.NoCodeRequired
                    is SmsVerifyOutcome.QueryFailed -> AuthSmsOutcome.QueryFailed(code.safeSummary)
                }
            }
            is SmsVerifyOutcome.NotRequired -> AuthSmsOutcome.NoCodeRequired
            is SmsVerifyOutcome.QueryFailed -> AuthSmsOutcome.QueryFailed(status.safeSummary)
        }
    }

    fun applyLoginUrls(login: LoginParseResult.Success) {
        client.keepUrl = login.keepUrl
        client.termUrl = login.termUrl
        client.keepRetry = login.keepRetrySeconds.toString()
        logger.info("Keep Url: ${safeUrlOf(login.keepUrl)}")
        logger.info("Term Url: ${safeUrlOf(login.termUrl)}")
        logger.info("Keep Retry: $login.keepRetrySeconds")
    }

    fun recordContractFailure(stage: DiagnosticStage, reason: ContractFailure, safeSummary: String) {
        val result: DiagnosticResult = reason.asDiagnosticResult(safeSummary)
        NetDiagnostics.record(stage, result)
    }

    private fun promptSmsCode(): AuthSmsOutcome {
        while (true) {
            print("Input Code: ")
            val input = readlnOrNull()
            if (input != null) {
                val code = input.trim()
                if (code.isNotBlank()) {
                    return AuthSmsOutcome.CodeProvided(code)
                }
            }
        }
    }

    private fun degradeHeartbeat(detail: String) {
        logger.error("Heartbeat failed: $detail")
        client.apply(AuthEvent.HEARTBEAT_FAILURE)
        States.isLogged = false
    }

    private fun finishTerm() {
        client.apply(AuthEvent.TERMINATED)
        States.isLogged = false
        if (!States.isRunning) {
            client.transitionTo(AuthState.SHUTDOWN)
        }
        logger.info("Session terminated.")
    }
}