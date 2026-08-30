package com.rsplwe.esurfing.diagnostics

/**
 * Defense-in-depth redaction for free-text fields before they reach JSONL.
 *
 * The primary guarantee of the model is structural: events have no field that
 * can carry a password, SMS code, ticket, cookie, key/IV or request body. This
 * redactor is the second line of defence: it strips exact sensitive terms
 * (e.g. known canary/secret values supplied by the caller) and redacts common
 * `name=value` secret shapes before a summary is serialised.
 */
object DiagnosticRedactor {

    const val REDACTED: String = "[REDACTED]"

    /** Common `name=value` secret shapes that must never survive in output. */
    val DEFAULT_PATTERNS: List<Regex> = listOf(
        Regex("""(?i)(passwd|password|pwd)\s*[=:]\s*\S+"""),
        Regex("""(?i)(smscode|verificationcode|cookie|ticket)\s*[=:]\s*\S+"""),
        Regex("""(?i)(zsmkey|key|iv)\s*[=:]\s*\S+"""),
    )

    fun redact(
        text: String,
        sensitiveTerms: Collection<String> = emptyList(),
        patterns: List<Regex> = DEFAULT_PATTERNS,
    ): String {
        var out = text
        for (term in sensitiveTerms) {
            if (term.isNotEmpty()) {
                out = out.replace(term, REDACTED)
            }
        }
        for (pattern in patterns) {
            out = pattern.replace(out, REDACTED)
        }
        return out
    }
}
