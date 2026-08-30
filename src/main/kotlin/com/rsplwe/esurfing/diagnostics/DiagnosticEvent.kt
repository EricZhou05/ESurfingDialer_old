package com.rsplwe.esurfing.diagnostics

/**
 * One immutable diagnostic event, ready to be written as a single JSONL line.
 *
 * [evidenceRef] is only a *reference* to an evidence file (e.g.
 * `sensitive/0001.bin`); raw evidence content is never embedded here.
 * [truncated] marks that the referenced evidence was truncated at capture.
 */
data class DiagnosticEvent(
    val runId: String,
    val attemptId: String,
    val correlationId: String,
    val stage: DiagnosticStage,
    val timestampEpochMillis: Long,
    val durationMs: Long?,
    val result: DiagnosticResult,
    val truncated: Boolean,
    val evidenceRef: String?,
) {
    val layer: ResultLayer get() = result.layer
    val outcome: ResultOutcome get() = result.outcome
    val errorCode: String? get() = result.errorCode?.code
    val safeSummary: String get() = result.safeSummary
}
