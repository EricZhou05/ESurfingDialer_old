package com.rsplwe.esurfing.protocol

/**
 * Typed outcome of a SMS verification query. The three verdicts are distinct:
 * "needs a code", "no code needed" and "could not determine" are NEVER
 * collapsed into a shared `false`.
 */
sealed interface SmsVerifyOutcome {

    /** The query positively answered: a verification code applies. */
    data object RequiresVerification : SmsVerifyOutcome

    /** The query positively answered: no verification code is needed. */
    data object NotRequired : SmsVerifyOutcome

    /** The query itself failed; [safeSummary] is a non-secret classification. */
    data class QueryFailed(val reason: SmsQueryFailure, val safeSummary: String) : SmsVerifyOutcome
}

/** Stable classification of why a SMS query failed (no HTTP/body content). */
enum class SmsQueryFailure {
    /** No endpoint URL is configured for the requested query type. */
    ENDPOINT_MISSING,

    /** The endpoint answered with a non-2xx status. */
    HTTP_ERROR,

    /** The body is empty, not JSON, or lacks the expected `rescode` field. */
    MALFORMED_RESPONSE,

    /** Transport-level failure (timeout, DNS, connection refused, IO). */
    TRANSPORT,
}

/**
 * How [com.rsplwe.esurfing.Client.authorization] treats the SMS query result:
 * either a code to embed in the login request, a positive "no code", or a query
 * failure that must abort the login attempt.
 */
sealed interface AuthSmsOutcome {

    data object NoCodeRequired : AuthSmsOutcome

    data class CodeProvided(val code: String) : AuthSmsOutcome

    data class QueryFailed(val safeSummary: String) : AuthSmsOutcome
}