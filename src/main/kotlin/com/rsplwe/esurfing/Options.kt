package com.rsplwe.esurfing

import com.rsplwe.esurfing.diagnostics.RunMode

/**
 * Where the login password was configured. Kept as a *name* so startup logging
 * can record the source without ever echoing the value; default stays
 * [CLI_FLAG] (the legacy `-p` path) for backward compatibility.
 */
enum class PasswordSource {
    /** Legacy `-p/--password` on the command line (residual risk: visible to ps). */
    CLI_FLAG,

    /** `--password-file <path>`: first line of a restricted-permission file. */
    CLI_FILE,

    /** `--password-stdin`: a single line read from standard input. */
    STDIN,

    /** `ESURFING_PASSWORD` environment variable (residual risk: /proc/<pid>/environ). */
    ENV,
}

data class Options(
    val loginUser: String,
    val loginPassword: String,
    val smsCode: String,
    val passwordSource: PasswordSource = PasswordSource.CLI_FLAG,

    /** Diagnostics evidence root; null = diagnostics disabled (backward compatible). */
    val diagnosticsRoot: String? = null,
    val diagnosticMode: RunMode = RunMode.NORMAL,
    val maxPayloadBytes: Long = DEFAULT_MAX_PAYLOAD_BYTES,
    val retentionMaxRuns: Int = DEFAULT_RETENTION_MAX_RUNS,
    val retentionMaxAgeDays: Int = DEFAULT_RETENTION_MAX_AGE_DAYS,
) {
    companion object {
        const val DEFAULT_MAX_PAYLOAD_BYTES: Long = 128L * 1024
        const val DEFAULT_RETENTION_MAX_RUNS: Int = 30
        const val DEFAULT_RETENTION_MAX_AGE_DAYS: Int = 90
    }
}