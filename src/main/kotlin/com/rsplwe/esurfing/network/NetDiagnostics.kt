package com.rsplwe.esurfing.network

import com.rsplwe.esurfing.diagnostics.DiagnosticContext
import com.rsplwe.esurfing.diagnostics.DiagnosticEvent
import com.rsplwe.esurfing.diagnostics.DiagnosticResult
import com.rsplwe.esurfing.diagnostics.DiagnosticStage
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-process wiring from the network layer into the Todo-2 diagnostics
 * contract (new file only — committed diagnostics models are not modified).
 *
 * Every classified HTTP/transport outcome is recorded as one [DiagnosticEvent]
 * carrying the four-layer typed result. A run keeps a single [DiagnosticContext]
 * so all events of the process share run/attempt/correlation IDs.
 *
 * [post] cannot know which lifecycle stage its caller is in, so the stage is
 * selectable per thread via [withStage]; it defaults to [DiagnosticStage.SESSION_ZSM]
 * until the authentication lifecycle (Todo 6) begins choosing explicit stages.
 */
object NetDiagnostics {

    private val recorded = CopyOnWriteArrayList<DiagnosticEvent>()
    private val postStage = ThreadLocal<DiagnosticStage>()

    @Volatile
    private var activeContext: DiagnosticContext = DiagnosticContext.start()

    /** All events recorded since the last [resetForTest]. */
    fun events(): List<DiagnosticEvent> = recorded.toList()

    /** The context shared by every event recorded on this thread/process. */
    fun context(): DiagnosticContext = activeContext

    /** Resets the event log and starts a fresh context (test isolation; not used in production). */
    fun resetForTest() {
        recorded.clear()
        activeContext = DiagnosticContext.start()
    }

    /**
     * Runs [block] with [stage] selected for any [post] call made on the same
     * thread; the selection is restored afterwards.
     */
    fun <T> withStage(stage: DiagnosticStage, block: () -> T): T {
        postStage.set(stage)
        return try {
            block()
        } finally {
            postStage.remove()
        }
    }

    /** The stage [post] records its events under on the current thread. */
    fun currentPostStage(): DiagnosticStage = postStage.get() ?: DiagnosticStage.SESSION_ZSM

    /** Records one classified outcome as a diagnostic event. */
    fun record(stage: DiagnosticStage, result: DiagnosticResult, durationMs: Long? = null) {
        val event = activeContext.event(stage = stage, result = result, durationMs = durationMs)
        recorded.add(event)
    }
}