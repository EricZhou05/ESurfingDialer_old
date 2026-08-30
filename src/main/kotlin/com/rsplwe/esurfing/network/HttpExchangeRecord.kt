package com.rsplwe.esurfing.network

import com.rsplwe.esurfing.diagnostics.DiagnosticStage
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response

/**
 * One hop of a manually-followed redirect chain. [safeLocation] is the
 * sanitised (scheme/host/path only) target, and the CDC headers that were
 * consumed on this hop are kept so each hop's area/schoolId/domain update is
 * observable afterwards.
 */
data class RedirectHop(
    val safeLocation: String,
    val status: Int,
    val area: String?,
    val schoolId: String?,
    val domain: String?,
)

/**
 * Diagnostic snapshot of a single HTTP exchange. No raw Location query,
 * no cookies and no body content is ever stored here: [safeUrl] and
 * [redirectChain] use the sanitised URL form, [selectedHeaders] only includes
 * the CDC-relevant non-secret headers, and [bodyBytes] is a length, never
 * content.
 */
data class HttpExchangeRecord(
    val stage: DiagnosticStage,
    val safeUrl: String,
    val status: Int?,
    val selectedHeaders: Map<String, String>,
    val contentType: String?,
    val bodyBytes: Long?,
    val durationMs: Long,
    val redirectChain: List<RedirectHop>,
)

/**
 * Sanitised URL form for diagnostics: scheme://host/path with the query,
 * fragment and userinfo removed. Never leaks a query string or credentials.
 */
fun safeUrlOf(url: String): String {
    val parsed = url.toHttpUrlOrNull() ?: return "<unparsable-url>"
    return parsed.newBuilder()
        .username("")
        .password("")
        .query(null)
        .fragment(null)
        .build()
        .toString()
}

/**
 * The subset of response headers safe to record: CDC routing headers and the
 * content type. Location (may carry a query) and Set-Cookie are deliberately
 * excluded by secret discipline.
 */
fun selectedHeadersOf(response: Response): Map<String, String> {
    val selected = LinkedHashMap<String, String>()
    for (name in listOf("area", "schoolid", "domain", "content-type")) {
        response.header(name)?.let { selected[name] = it }
    }
    return selected
}

/** Builds the per-exchange record from an OkHttp [Response] (body not consumed). */
fun buildExchangeRecord(
    stage: DiagnosticStage,
    safeUrl: String,
    response: Response,
    durationMs: Long,
    redirectChain: List<RedirectHop>,
): HttpExchangeRecord = HttpExchangeRecord(
    stage = stage,
    safeUrl = safeUrl,
    status = response.code,
    selectedHeaders = selectedHeadersOf(response),
    contentType = response.header("content-type"),
    bodyBytes = response.body?.contentLength(),
    durationMs = durationMs,
    redirectChain = redirectChain,
)