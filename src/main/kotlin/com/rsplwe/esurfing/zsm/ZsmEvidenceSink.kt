package com.rsplwe.esurfing.zsm

import com.rsplwe.esurfing.diagnostics.DiagnosticIds
import com.rsplwe.esurfing.diagnostics.EvidenceKind
import com.rsplwe.esurfing.diagnostics.EvidenceInput
import com.rsplwe.esurfing.diagnostics.EvidenceRun
import com.rsplwe.esurfing.diagnostics.EvidenceStore
import com.rsplwe.esurfing.diagnostics.Redactor
import com.rsplwe.esurfing.diagnostics.RetentionPolicy
import com.rsplwe.esurfing.diagnostics.RunMode
import com.rsplwe.esurfing.diagnostics.WriteOutcome
import java.nio.file.Path

/**
 * Minimal sensitive-only sink for raw ZSM bytes.
 *
 * Forwards the original ZSM into [EvidenceStore] (kind [EvidenceKind.ZSM_RAW],
 * `captureSensitive = true`): the raw bytes reach disk **only** under an
 * explicit [RunMode.SENSITIVE] run (in `sensitive/`), with an independent
 * redacted derivative alongside (in `redacted/`). Under [RunMode.NORMAL] the
 * store refuses the raw write with a typed [WriteOutcome.Failed] and nothing
 * is written. The old unconditional `algo_dump_*.bin` current-directory write
 * is gone.
 *
 * The [EvidenceStore] itself is committed infrastructure — this wrapper only
 * supplies a fixed, small, domain-tuned configuration for ZSM evidence.
 */
class ZsmEvidenceSink internal constructor(
    private val run: EvidenceRun,
    private val attemptId: String,
    private val correlationId: String,
) {

    val runId: String = run.runId
    val mode: RunMode = run.mode

    fun saveRawZsm(zsm: ByteArray, stage: String): WriteOutcome =
        run.write(
            EvidenceInput(
                kind = EvidenceKind.ZSM_RAW,
                stage = stage,
                runId = run.runId,
                attemptId = attemptId,
                correlationId = correlationId,
                payload = zsm,
                captureSensitive = true,
            ),
        )

    companion object {
        private const val MAX_FILE_BYTES = 64L * 1024
        private const val MAX_PAYLOAD_BYTES = 128L * 1024
        private const val RUN_QUOTA_BYTES = 4L * 1024 * 1024

        /**
         * Open a raw-ZSM evidence run under [root]. Production wiring is Todo 7
         * (the CLI configures diagnostics); tests and Todo 7 call this directly.
         */
        fun open(
            root: Path,
            runId: String = DiagnosticIds.newRunId(),
            mode: RunMode = RunMode.SENSITIVE,
        ): ZsmEvidenceSink {
            val store = EvidenceStore(
                root = root,
                redactor = Redactor.standard(),
                maxFileBytes = MAX_FILE_BYTES,
                maxPayloadBytes = MAX_PAYLOAD_BYTES,
                runQuotaBytes = RUN_QUOTA_BYTES,
                retention = RetentionPolicy(maxRuns = 30, maxAgeDays = 90),
            )
            val run = store.beginRun(runId, mode)
            return ZsmEvidenceSink(
                run = run,
                attemptId = DiagnosticIds.newAttemptId(),
                correlationId = DiagnosticIds.newCorrelationId(),
            )
        }
    }
}