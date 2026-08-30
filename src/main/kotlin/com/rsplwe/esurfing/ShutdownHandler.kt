package com.rsplwe.esurfing

import com.rsplwe.esurfing.auth.AuthEvent
import com.rsplwe.esurfing.auth.AuthState
import org.apache.log4j.Logger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reliable shutdown coordinator (Todo 7).
 *
 * Registered by [DialerApp] as a JVM shutdown hook and also invoked when the
 * run loop exits naturally. Guarantees:
 *  - every term/session failure is captured and logged as a typed, non-stack
 *    message (no secret, no stack trace in the ordinary log)
 *  - the diagnostics event flush + manifest finalise ALWAYS run, even when
 *    term fails (the evidence run is idempotent and never throws)
 *  - afterwards the auth state machine is driven to [AuthState.SHUTDOWN] so no
 *    further request is issued (Todo 6 contract)
 */
class ShutdownHandler(
    private val client: Client,
    private val runner: DiagnosticsRunner?,
) {
    private val logger: Logger = Logger.getLogger(ShutdownHandler::class.java)
    private val done = AtomicBoolean(false)

    fun shutdown() {
        if (!done.compareAndSet(false, true)) return

        States.isRunning = false
        try {
            if (Session.isInitialized()) {
                if (States.isLogged) {
                    client.term()
                }
                Session.free()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.warn("Shutdown interrupted while terminating the session (no stack logged).")
        } catch (e: Exception) {
            handleTermFailure(e)
        }

        // Event/manifest flush is unconditional — even when term failed or the
        // process is terminating without a live session.
        try {
            runner?.flushEventsAndFinalize()
        } catch (e: Exception) {
            logger.warn("Evidence flush failed during shutdown: ${e::class.java.simpleName} (no stack logged).")
        }

        // Terminal state: scans/authorization/heartbeat/no requests after this.
        try {
            client.apply(AuthEvent.SHUTDOWN)
        } catch (e: Exception) {
            logger.warn("Could not transition to SHUTDOWN: ${e::class.java.simpleName} (no stack logged).")
        }
        logger.info("Shutting down.")
        println("Shutting down...")
    }

    private fun handleTermFailure(e: Exception) {
        val summary = sequenceOf(
            e.message?.takeIf { it.isNotBlank() },
            e::class.java.simpleName,
        ).filterNotNull().joinToString(": ")
        logger.error("Session/term failure during shutdown -> $summary")
    }
}