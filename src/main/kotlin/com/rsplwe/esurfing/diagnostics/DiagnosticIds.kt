package com.rsplwe.esurfing.diagnostics

import java.util.UUID

/**
 * Stable identifier generation for diagnostics. One `runId` per application
 * start, one `attemptId` per authentication attempt, and a `correlationId`
 * shared across every event of the same attempt so stages can be correlated.
 */
object DiagnosticIds {

    fun newRunId(): String = UUID.randomUUID().toString()

    fun newAttemptId(): String = UUID.randomUUID().toString()

    fun newCorrelationId(): String = UUID.randomUUID().toString()
}

/**
 * The correlation scope of one authentication attempt: every event built from
 * this context shares the same run/attempt/correlation IDs.
 */
data class DiagnosticContext(
    val runId: String,
    val attemptId: String,
    val correlationId: String,
) {

    fun event(
        stage: DiagnosticStage,
        result: DiagnosticResult,
        timestampEpochMillis: Long = System.currentTimeMillis(),
        durationMs: Long? = null,
        truncated: Boolean = false,
        evidenceRef: String? = null,
    ): DiagnosticEvent = DiagnosticEvent(
        runId = runId,
        attemptId = attemptId,
        correlationId = correlationId,
        stage = stage,
        timestampEpochMillis = timestampEpochMillis,
        durationMs = durationMs,
        result = result,
        truncated = truncated,
        evidenceRef = evidenceRef,
    )

    companion object {
        fun start(): DiagnosticContext = startAttempt(DiagnosticIds.newRunId())

        fun startAttempt(runId: String): DiagnosticContext =
            DiagnosticContext(runId, DiagnosticIds.newAttemptId(), DiagnosticIds.newCorrelationId())
    }
}
