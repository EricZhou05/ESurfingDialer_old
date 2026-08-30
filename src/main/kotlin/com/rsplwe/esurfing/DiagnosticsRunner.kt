package com.rsplwe.esurfing

import com.rsplwe.esurfing.diagnostics.DiagnosticEventJson
import com.rsplwe.esurfing.diagnostics.DiagnosticIds
import com.rsplwe.esurfing.diagnostics.EvidenceInput
import com.rsplwe.esurfing.diagnostics.EvidenceKind
import com.rsplwe.esurfing.diagnostics.EvidenceRun
import com.rsplwe.esurfing.diagnostics.EvidenceStore
import com.rsplwe.esurfing.diagnostics.Redactor
import com.rsplwe.esurfing.diagnostics.RetentionPolicy
import com.rsplwe.esurfing.diagnostics.RunMode
import com.rsplwe.esurfing.diagnostics.WriteOutcome
import com.rsplwe.esurfing.network.NetDiagnostics
import com.rsplwe.esurfing.zsm.ZsmEvidenceSink
import java.nio.file.Path
import org.apache.log4j.Logger

/**
 * Configurable diagnostic evidence wiring (Todo 7).
 *
 * Owns one [EvidenceStore] run whose root / mode / payload limit / retention
 * come from the CLI/environment resolution ([CliParser] -> [Options]); exposes
 * the [ZsmEvidenceSink] for raw ZSM capture and flushes the in-process
 * [NetDiagnostics] events plus the manifest on shutdown.
 *
 * The run begins as soon as [open] is called, so a manifest always exists;
 * [flushEventsAndFinalize] is idempotent and never throws — storage failures
 * are logged as typed, non-secret messages.
 */
class DiagnosticsRunner private constructor(
    private val run: EvidenceRun,
    private val attemptId: String,
    private val correlationId: String,
    private val payloadLimit: Long,
) {

    val runId: String = run.runId
    val runDir: Path = run.runDir
    val mode: RunMode = run.mode

    private val logger: Logger = Logger.getLogger(DiagnosticsRunner::class.java)

    /** Bind the raw-ZSM evidence sink to [Session]; null detaches (fully offline). */
    fun attachToSession() {
        Session.attachZsmEvidenceSink(ZsmEvidenceSink(run, attemptId, correlationId))
    }

    /**
     * Writes all events recorded so far (chunked to the payload limit) as
     * EVENT_JSONL and finalises the manifest. Called from the shutdown handler
     * whether term succeeded or failed; archival failures are logged typed and
     * never escape.
     */
    fun flushEventsAndFinalize() {
        val events = NetDiagnostics.events()
        val ctx = NetDiagnostics.context()
        val lines = DiagnosticEventJson.encodeLines(events).lineSequence().filter { it.isNotBlank() }.toList()
        var pending = StringBuilder()
        for (line in lines) {
            val candidate = if (pending.isEmpty()) line else "$pending\n$line"
            if (candidate.toByteArray().size.toLong() > payloadLimit) {
                if (pending.isNotEmpty()) {
                    writeEventChunk(ctx.runId, ctx.attemptId, ctx.correlationId, pending.toString())
                    pending = StringBuilder()
                }
                writeEventChunk(ctx.runId, ctx.attemptId, ctx.correlationId, line)
            } else {
                pending = StringBuilder(candidate)
            }
        }
        if (pending.isNotEmpty()) {
            writeEventChunk(ctx.runId, ctx.attemptId, ctx.correlationId, pending.toString())
        }
        run.finalizeManifest()
        logger.info("Diagnostics run $runId finalized (mode=${mode.name.lowercase()}).")
    }

    private fun writeEventChunk(runId: String, attemptId: String, correlationId: String, jsonl: String) {
        when (val outcome = run.write(
            EvidenceInput(
                kind = EvidenceKind.EVENT_JSONL,
                stage = "shutdown",
                runId = runId,
                attemptId = attemptId,
                correlationId = correlationId,
                payload = jsonl.toByteArray(),
                captureSensitive = false,
            ),
        )) {
            is WriteOutcome.Saved -> logger.info("Flushed ${jsonl.count { it == '\n' } + 1} diagnostic event line(s).")
            is WriteOutcome.Failed -> logger.warn(
                "Diagnostic event flush skipped: ${outcome.error::class.java.simpleName} " +
                    "(no secrets logged).",
            )
        }
    }

    companion object {
        private const val QUOTA_BYTES = 4L * 1024 * 1024

        /**
         * Open a diagnostics run under [root]. Payload limit and retention come
         * from configuration; the per-file soft limit equals the payload limit so
         * oversized content is rejected (typed) rather than silently truncated.
         */
        fun open(
            root: Path,
            mode: RunMode = RunMode.NORMAL,
            payloadLimit: Long = Options.DEFAULT_MAX_PAYLOAD_BYTES,
            retentionMaxRuns: Int = Options.DEFAULT_RETENTION_MAX_RUNS,
            retentionMaxAgeDays: Int = Options.DEFAULT_RETENTION_MAX_AGE_DAYS,
        ): DiagnosticsRunner {
            val store = EvidenceStore(
                root = root,
                redactor = Redactor.standard(),
                maxFileBytes = payloadLimit,
                maxPayloadBytes = payloadLimit,
                runQuotaBytes = QUOTA_BYTES,
                retention = RetentionPolicy(retentionMaxRuns, retentionMaxAgeDays),
            )
            val run = store.beginRun(DiagnosticIds.newRunId(), mode)
            return DiagnosticsRunner(run, DiagnosticIds.newAttemptId(), DiagnosticIds.newCorrelationId(), payloadLimit)
        }
    }
}