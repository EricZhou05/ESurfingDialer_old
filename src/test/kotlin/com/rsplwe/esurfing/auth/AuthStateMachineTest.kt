package com.rsplwe.esurfing.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * State-table verification of the authentication lifecycle machine: illegal
 * transitions (login before a session, requests from UNINITIALIZED/SHUTDOWN)
 * are refused; badge-of-login only via LOGIN_SUCCESS from READY; heartbeat
 * failure degrades; term releases and is idempotent; shutdown absorbs and then
 * blocks every further request.
 */
class AuthStateMachineTest {

    private fun next(from: AuthState, event: AuthEvent) = AuthStateMachine.next(from, event)

    @Test
    fun uninitialized_cannotLogin() {
        assertNull(next(AuthState.UNINITIALIZED, AuthEvent.LOGIN_SUCCESS), "LOGIN_SUCCESS before a session is illegal")
    }

    @Test
    fun uninitialized_heartbeat_isNotAllowed() {
        assertFalse(AuthStateMachine.canIssue(AuthState.UNINITIALIZED, AuthRequestKind.HEARTBEAT))
    }

    @Test
    fun uninitialized_term_isNotAllowed() {
        assertFalse(AuthStateMachine.canIssue(AuthState.UNINITIALIZED, AuthRequestKind.TERM))
    }

    @Test
    fun uninitialized_cannotLoginRequest() {
        assertFalse(AuthStateMachine.canIssue(AuthState.UNINITIALIZED, AuthRequestKind.LOGIN))
    }

    @Test
    fun sessionInitialized_movesToReady() {
        assertEquals(AuthState.READY, next(AuthState.UNINITIALIZED, AuthEvent.SESSION_INITIALIZED))
    }

    @Test
    fun degradedReauth_reusesSessionPath() {
        assertEquals(AuthState.READY, next(AuthState.DEGRADED, AuthEvent.SESSION_INITIALIZED))
        assertEquals(AuthState.READY, next(AuthState.LOGGED, AuthEvent.SESSION_INITIALIZED))
    }

    @Test
    fun loginSuccess_fromReady_marksLogged() {
        assertEquals(AuthState.LOGGED, next(AuthState.READY, AuthEvent.LOGIN_SUCCESS))
    }

    @Test
    fun loginSuccess_fromAnyOtherState_isIllegal() {
        assertNull(next(AuthState.UNINITIALIZED, AuthEvent.LOGIN_SUCCESS))
        assertNull(next(AuthState.DEGRADED, AuthEvent.LOGIN_SUCCESS))
        assertNull(next(AuthState.LOGGED, AuthEvent.LOGIN_SUCCESS))
    }

    @Test
    fun loginFailure_staysUnlogged() {
        assertEquals(AuthState.READY, next(AuthState.READY, AuthEvent.LOGIN_FAILURE))
        assertTrue(next(AuthState.READY, AuthEvent.LOGIN_FAILURE) != AuthState.LOGGED)
    }

    @Test
    fun heartbeatSuccess_keepsLogged() {
        assertEquals(AuthState.LOGGED, next(AuthState.LOGGED, AuthEvent.HEARTBEAT_SUCCESS))
    }

    @Test
    fun heartbeatFailure_degrades() {
        assertEquals(AuthState.DEGRADED, next(AuthState.LOGGED, AuthEvent.HEARTBEAT_FAILURE))
    }

    @Test
    fun heartbeat_fromDegraded_isIllegalAndBanned() {
        assertNull(next(AuthState.DEGRADED, AuthEvent.HEARTBEAT_FAILURE))
        assertFalse(AuthStateMachine.canIssue(AuthState.DEGRADED, AuthRequestKind.HEARTBEAT))
    }

    @Test
    fun degraded_allowsTerm() {
        assertTrue(AuthStateMachine.canIssue(AuthState.DEGRADED, AuthRequestKind.TERM))
        assertEquals(AuthState.UNINITIALIZED, next(AuthState.DEGRADED, AuthEvent.TERMINATED))
    }

    @Test
    fun term_releasesToUninitialized() {
        assertEquals(AuthState.UNINITIALIZED, next(AuthState.READY, AuthEvent.TERMINATED))
        assertEquals(AuthState.UNINITIALIZED, next(AuthState.LOGGED, AuthEvent.TERMINATED))
    }

    @Test
    fun termAfterRelease_isNotAllowed() {
        assertFalse(AuthStateMachine.canIssue(AuthState.UNINITIALIZED, AuthRequestKind.TERM))
    }

    @Test
    fun authAttempt_forgetsOldState() {
        assertEquals(AuthState.UNINITIALIZED, next(AuthState.LOGGED, AuthEvent.AUTH_ATTEMPT_STARTED))
        assertEquals(AuthState.UNINITIALIZED, next(AuthState.DEGRADED, AuthEvent.AUTH_ATTEMPT_STARTED))
        assertEquals(AuthState.UNINITIALIZED, next(AuthState.READY, AuthEvent.AUTH_ATTEMPT_STARTED))
    }

    @Test
    fun shutdown_absorbsAnyState() {
        for (state in AuthState.entries) {
            assertEquals(AuthState.SHUTDOWN, next(state, AuthEvent.SHUTDOWN), "SHUTDOWN must absorb $state")
        }
    }

    @Test
    fun shutdown_blocksNewAttempts() {
        assertNull(next(AuthState.SHUTDOWN, AuthEvent.AUTH_ATTEMPT_STARTED))
    }

    @Test
    fun shutdown_blocksAllRequests() {
        for (kind in AuthRequestKind.entries) {
            assertFalse(AuthStateMachine.canIssue(AuthState.SHUTDOWN, kind), "SHUTDOWN must block $kind")
        }
    }
}