package com.rsplwe.esurfing.auth

/**
 * Lifecycle states of the authentication flow.
 *
 * - [UNINITIALIZED] — no usable session/cipher; nothing may log in, keep or term.
 * - [READY] — session cipher loaded; a login MAY be attempted.
 * - [LOGGED] — login contract satisfied; heartbeats run.
 * - [DEGRADED] — the last heartbeat failed; the session needs re-authentication.
 * - [SHUTDOWN] — terminal; no further requests are issued.
 */
enum class AuthState {
    UNINITIALIZED,
    READY,
    LOGGED,
    DEGRADED,
    SHUTDOWN,
}

/** Events that drive a state transition. */
enum class AuthEvent {
    /** A new authorization attempt begins (forgets all cached credential state). */
    AUTH_ATTEMPT_STARTED,

    /** The ZSM session was initialized successfully. */
    SESSION_INITIALIZED,

    LOGIN_SUCCESS,
    LOGIN_FAILURE,
    HEARTBEAT_SUCCESS,
    HEARTBEAT_FAILURE,
    TERMINATED,

    /** Explicit shutdown: terminal for the rest of the process. */
    SHUTDOWN,
}

/** The kinds of protocol request the state machine gates. */
enum class AuthRequestKind {
    LOGIN,
    HEARTBEAT,
    TERM,
}

/**
 * The legal state-transition table for the authentication lifecycle.
 *
 * `next(from, event)` returns `null` for an illegal transition — the caller
 * refuses the request and records a typed failure instead of acting. The
 * machine is a pure function so every guard is unit-testable without requests.
 */
object AuthStateMachine {

    private val transitions: Map<Pair<AuthState, AuthEvent>, AuthState> = mapOf(
        (AuthState.UNINITIALIZED to AuthEvent.AUTH_ATTEMPT_STARTED) to AuthState.UNINITIALIZED,
        (AuthState.READY to AuthEvent.AUTH_ATTEMPT_STARTED) to AuthState.UNINITIALIZED,
        (AuthState.LOGGED to AuthEvent.AUTH_ATTEMPT_STARTED) to AuthState.UNINITIALIZED,
        (AuthState.DEGRADED to AuthEvent.AUTH_ATTEMPT_STARTED) to AuthState.UNINITIALIZED,

        // Re-authentication reuses the session-initialization path.
        (AuthState.UNINITIALIZED to AuthEvent.SESSION_INITIALIZED) to AuthState.READY,
        (AuthState.DEGRADED to AuthEvent.SESSION_INITIALIZED) to AuthState.READY,
        (AuthState.LOGGED to AuthEvent.SESSION_INITIALIZED) to AuthState.READY,

        // A failed login never moves into LOGGED ("unlogged" is READY).
        (AuthState.READY to AuthEvent.LOGIN_SUCCESS) to AuthState.LOGGED,
        (AuthState.READY to AuthEvent.LOGIN_FAILURE) to AuthState.READY,

        (AuthState.LOGGED to AuthEvent.HEARTBEAT_SUCCESS) to AuthState.LOGGED,
        (AuthState.LOGGED to AuthEvent.HEARTBEAT_FAILURE) to AuthState.DEGRADED,

        (AuthState.READY to AuthEvent.TERMINATED) to AuthState.UNINITIALIZED,
        (AuthState.LOGGED to AuthEvent.TERMINATED) to AuthState.UNINITIALIZED,
        (AuthState.DEGRADED to AuthEvent.TERMINATED) to AuthState.UNINITIALIZED,
    )

    /**
     * The state reached by applying [event] from [from], or `null` when the
     * transition is illegal. [AuthEvent.SHUTDOWN] is absorbing from every state.
     */
    fun next(from: AuthState, event: AuthEvent): AuthState? {
        if (event == AuthEvent.SHUTDOWN) return AuthState.SHUTDOWN
        return transitions[from to event]
    }

    /** Whether [kind] requests may currently be issued from [state]. */
    fun canIssue(state: AuthState, kind: AuthRequestKind): Boolean = when (kind) {
        AuthRequestKind.LOGIN -> state == AuthState.READY
        AuthRequestKind.HEARTBEAT -> state == AuthState.LOGGED
        AuthRequestKind.TERM ->
            state == AuthState.READY || state == AuthState.LOGGED || state == AuthState.DEGRADED
    }
}