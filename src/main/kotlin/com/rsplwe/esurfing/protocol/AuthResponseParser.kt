package com.rsplwe.esurfing.protocol

import com.rsplwe.esurfing.diagnostics.BusinessErrorCode
import com.rsplwe.esurfing.diagnostics.BusinessResult
import com.rsplwe.esurfing.diagnostics.DiagnosticResult
import com.rsplwe.esurfing.diagnostics.ProtocolErrorCode
import com.rsplwe.esurfing.diagnostics.ProtocolResult
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser

/**
 * Why an XML protocol response failed its contract. The three main families —
 * format error, missing required field, business rejection — plus the interval
 * and URL sub-checks are kept apart so a caller can tell them all apart.
 */
enum class ContractFailure {
    /** The body is not well-formed XML (or not XML at all). */
    FORMAT_MALFORMED,

    /** A required field is absent or blank. */
    MISSING_FIELD,

    /** The response carries a non-`success` business result code. */
    BUSINESS_REJECTED,

    /** keep-retry / interval is blank, non-numeric, zero/negative or out of range. */
    INVALID_INTERVAL,

    /** keep-url / term-url is blank or not an absolute http(s) endpoint. */
    INVALID_URL,

    /** The response payload could not be decrypted. */
    DECRYPT,

    /** The HTTP exchange itself failed (no body to parse). */
    REQUEST_FAILED,
}

/**
 * Maps a contract failure to the layered diagnostics result used for events.
 * Business rejections surface at the business layer; everything else is a
 * protocol-layer failure. [INVALID_VALUE] is the ProtocolErrorCode for the
 * interval/URL guard and [REQUEST_FAILED] keeps a stable (safe) fallback.
 */
fun ContractFailure.asDiagnosticResult(safeSummary: String): DiagnosticResult = when (this) {
    ContractFailure.BUSINESS_REJECTED -> BusinessResult.Failure(BusinessErrorCode.REJECTED, safeSummary)
    ContractFailure.FORMAT_MALFORMED -> ProtocolResult.Failure(ProtocolErrorCode.XML_MALFORMED, safeSummary)
    ContractFailure.MISSING_FIELD -> ProtocolResult.Failure(ProtocolErrorCode.MISSING_FIELD, safeSummary)
    ContractFailure.DECRYPT -> ProtocolResult.Failure(ProtocolErrorCode.DECRYPT, safeSummary)
    ContractFailure.INVALID_INTERVAL,
    ContractFailure.INVALID_URL,
    ContractFailure.REQUEST_FAILED,
    -> ProtocolResult.Failure(ProtocolErrorCode.INVALID_VALUE, safeSummary)
}

/** Typed parse outcome of a ticket response. */
sealed interface TicketParseResult {
    data class Success(val ticket: String) : TicketParseResult
    data class Failure(val reason: ContractFailure, val safeSummary: String) : TicketParseResult
}

/** Typed parse outcome of a login response. */
sealed interface LoginParseResult {
    data class Success(val keepUrl: String, val termUrl: String, val keepRetrySeconds: Long) : LoginParseResult
    data class Failure(val reason: ContractFailure, val safeSummary: String) : LoginParseResult
}

/** Typed parse outcome of a heartbeat response. */
sealed interface HeartbeatParseResult {
    data class Success(val intervalSeconds: Long) : HeartbeatParseResult
    data class Failure(val reason: ContractFailure, val safeSummary: String) : HeartbeatParseResult
}

/** Typed parse outcome of a term response. */
sealed interface TermParseResult {
    data object Success : TermParseResult
    data class Failure(val reason: ContractFailure, val safeSummary: String) : TermParseResult
}

/**
 * Parse-don't-validate boundary for the decrypted XML protocol responses.
 *
 * A response is parsed ONCE into a typed [TicketParseResult] / [LoginParseResult]
 * / [HeartbeatParseResult] / [TermParseResult]. Everything downstream receives
 * typed values (or a typed failure) and never re-parses strings. Duplicate
 * fields resolve deterministically to the first non-blank value; malformed
 * (non-XML) and truncated bodies are distinguished from missing fields and from
 * business rejections.
 */
object AuthResponseParser {

    /** The only business result code the protocol contract accepts. */
    const val RESULT_OK = "success"

    fun parseTicket(decrypted: String): TicketParseResult {
        val doc = xmlDocument(decrypted) ?: return TicketParseResult.Failure(ContractFailure.FORMAT_MALFORMED, "ticket_not_xml")
        businessVerdict(doc)?.let { return TicketParseResult.Failure(it, "ticket_${it.name.lowercase()}") }
        val ticket = firstText(doc, "ticket")
        if (ticket.isNullOrBlank()) {
            return TicketParseResult.Failure(ContractFailure.MISSING_FIELD, "ticket_missing")
        }
        return TicketParseResult.Success(ticket)
    }

    fun parseLogin(decrypted: String): LoginParseResult {
        val doc = xmlDocument(decrypted) ?: return LoginParseResult.Failure(ContractFailure.FORMAT_MALFORMED, "login_not_xml")
        businessVerdict(doc)?.let { return LoginParseResult.Failure(it, "login_${it.name.lowercase()}") }

        val keepUrl = firstText(doc, "keep-url")
            ?: return LoginParseResult.Failure(ContractFailure.MISSING_FIELD, "login_keep_url_missing")
        if (!isValidEndpoint(keepUrl)) {
            return LoginParseResult.Failure(ContractFailure.INVALID_URL, "login_keep_url_invalid")
        }
        val termUrl = firstText(doc, "term-url")
            ?: return LoginParseResult.Failure(ContractFailure.MISSING_FIELD, "login_term_url_missing")
        if (!isValidEndpoint(termUrl)) {
            return LoginParseResult.Failure(ContractFailure.INVALID_URL, "login_term_url_invalid")
        }

        val retry = firstText(doc, "keep-retry")
        val elapsed = when (val outcome = parseIntervalSeconds(retry, "login_keep_retry")) {
            is IntervalOutcome.Valid -> outcome.seconds
            is IntervalOutcome.Invalid -> return LoginParseResult.Failure(outcome.reason, outcome.safeSummary)
        }
        return LoginParseResult.Success(keepUrl, termUrl, elapsed)
    }

    fun parseHeartbeat(decrypted: String): HeartbeatParseResult {
        val doc = xmlDocument(decrypted) ?: return HeartbeatParseResult.Failure(ContractFailure.FORMAT_MALFORMED, "heartbeat_not_xml")
        businessVerdict(doc)?.let { return HeartbeatParseResult.Failure(it, "heartbeat_${it.name.lowercase()}") }

        val interval = firstText(doc, "interval")
        return when (val outcome = parseIntervalSeconds(interval, "heartbeat_interval")) {
            is IntervalOutcome.Valid -> HeartbeatParseResult.Success(outcome.seconds)
            is IntervalOutcome.Invalid -> HeartbeatParseResult.Failure(outcome.reason, outcome.safeSummary)
        }
    }

    fun parseTerm(decrypted: String): TermParseResult {
        val doc = xmlDocument(decrypted) ?: return TermParseResult.Failure(ContractFailure.FORMAT_MALFORMED, "term_not_xml")
        businessVerdict(doc)?.let { return TermParseResult.Failure(it, "term_${it.name.lowercase()}") }
        return TermParseResult.Success
    }

    // --- internals ---

    private sealed interface IntervalOutcome {
        data class Valid(val seconds: Long) : IntervalOutcome
        data class Invalid(val reason: ContractFailure, val safeSummary: String) : IntervalOutcome
    }

    private fun parseIntervalSeconds(raw: String?, field: String): IntervalOutcome {
        if (raw.isNullOrBlank()) {
            return IntervalOutcome.Invalid(ContractFailure.MISSING_FIELD, "${field}_missing")
        }
        val seconds = raw.toLongOrNull()
        if (seconds == null) {
            return IntervalOutcome.Invalid(ContractFailure.INVALID_INTERVAL, "${field}_not_numeric")
        }
        if (seconds !in ProtocolLimits.MIN_INTERVAL_SECONDS..ProtocolLimits.MAX_INTERVAL_SECONDS) {
            return IntervalOutcome.Invalid(ContractFailure.INVALID_INTERVAL, "${field}_out_of_range")
        }
        return IntervalOutcome.Valid(seconds)
    }

    /** First non-blank element text for [tag] — deterministic for duplicates. */
    private fun firstText(doc: Document, tag: String): String? =
        doc.getElementsByTag(tag).firstOrNull { it.text().isNotBlank() }?.text()

    /** Business verdict: [ContractFailure.MISSING_FIELD] / [BUSINESS_REJECTED] or null when accepted. */
    private fun businessVerdict(doc: Document): ContractFailure? {
        val result = firstText(doc, "result") ?: return ContractFailure.MISSING_FIELD
        return if (result != RESULT_OK) ContractFailure.BUSINESS_REJECTED else null
    }

    private fun xmlDocument(decrypted: String): Document? {
        val trimmed = decrypted.trim()
        if (!trimmed.startsWith("<")) return null
        return Jsoup.parse(trimmed, "", Parser.xmlParser())
    }

    private fun isValidEndpoint(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val url = raw.trim().toHttpUrlOrNull() ?: return false
        return url.scheme == "http" || url.scheme == "https"
    }
}