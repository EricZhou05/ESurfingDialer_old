package com.rsplwe.esurfing.network

import com.rsplwe.esurfing.diagnostics.DiagnosticResult
import com.rsplwe.esurfing.diagnostics.DiagnosticStage
import com.rsplwe.esurfing.diagnostics.HttpErrorCode
import com.rsplwe.esurfing.diagnostics.HttpResult
import com.rsplwe.esurfing.diagnostics.TransportErrorCode
import com.rsplwe.esurfing.diagnostics.TransportResult
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Redirect protocol violations surfaced by [RedirectInterceptor] to the caller. */
enum class RedirectProtocolKind { REDIRECT_LIMIT, MISSING_LOCATION }

class RedirectProtocolException(val kind: RedirectProtocolKind) :
    IOException("redirect protocol: ${kind.name.lowercase()}")

/**
 * Stable machine-readable labels carried by [NetResult.Error.exception].
 * Safe to log and to assert on; never derived from stack traces.
 */
object NetErrorCode {
    const val REDIRECT_LIMIT = "REDIRECT_LIMIT"
    const val MISSING_LOCATION = "MISSING_LOCATION"
    const val EMPTY_BODY = "EMPTY_BODY"
    const val TIMEOUT = "TIMEOUT"
    const val DNS = "DNS"
    const val CONNECTION_REFUSED = "CONNECTION_REFUSED"
    const val TLS = "TLS"
    const val IO = "IO"
    const val UNKNOWN = "UNKNOWN"

    fun http(status: Int): String = "HTTP_$status"
}

fun elapsedMillis(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

/**
 * Classifies an already-executed response into a typed [NetResult]:
 * non-2xx -> Error(HTTP_<code>), 2xx with no body -> Error(EMPTY_BODY),
 * otherwise Success with the body handed to the caller. Every outcome records
 * one diagnostic event and the non-success paths close the response so
 * connections are returned to the pool.
 */
fun classifyResponse(response: Response, record: HttpExchangeRecord): NetResult<ResponseBody> {
    val stage = record.stage
    return when {
        !response.isSuccessful -> {
            val result: DiagnosticResult =
                HttpResult.Failure(HttpErrorCode.NON_2XX, NetErrorCode.http(response.code))
            NetDiagnostics.record(stage, result, record.durationMs)
            response.close()
            NetResult.Error(NetErrorCode.http(response.code), record)
        }

        (response.body == null || response.body!!.contentLength() == 0L) -> {
            val result: DiagnosticResult =
                HttpResult.Failure(HttpErrorCode.EMPTY_BODY, NetErrorCode.EMPTY_BODY)
            NetDiagnostics.record(stage, result, record.durationMs)
            response.close()
            NetResult.Error(NetErrorCode.EMPTY_BODY, record)
        }

        else -> {
            NetDiagnostics.record(stage, HttpResult.Success, record.durationMs)
            NetResult.Success(response.body!!, record)
        }
    }
}

/**
 * Classifies a transport / redirect-protocol exception into a typed failure,
 * closing the redirect chain bookkeeping and recording the diagnostic event.
 * A minimal exchange record is attached (status unknown); the response — if
 * the interceptor or caller had one open — is closed by the throwing side.
 */
fun classifyFailure(
    e: Throwable,
    stage: DiagnosticStage,
    safeUrl: String,
    durationMs: Long,
    existingChain: List<RedirectHop>? = null,
): NetResult<Nothing> {
    val chain = existingChain ?: RedirectInterceptor.takeChain()
    val record = HttpExchangeRecord(
        stage = stage,
        safeUrl = safeUrl,
        status = null,
        selectedHeaders = emptyMap(),
        contentType = null,
        bodyBytes = null,
        durationMs = durationMs,
        redirectChain = chain,
    )
    val (code, result) = when (e) {
        is RedirectProtocolException -> when (e.kind) {
            RedirectProtocolKind.REDIRECT_LIMIT ->
                NetErrorCode.REDIRECT_LIMIT to (HttpResult.Failure(HttpErrorCode.REDIRECT_LIMIT, NetErrorCode.REDIRECT_LIMIT)
                    as DiagnosticResult)
            RedirectProtocolKind.MISSING_LOCATION ->
                NetErrorCode.MISSING_LOCATION to (HttpResult.Failure(HttpErrorCode.MISSING_LOCATION, NetErrorCode.MISSING_LOCATION)
                    as DiagnosticResult)
        }

        is SocketTimeoutException -> {
            val code = if (e.message?.contains("connect") == true) {
                TransportErrorCode.CONNECT_TIMEOUT
            } else {
                TransportErrorCode.READ_TIMEOUT
            }
            NetErrorCode.TIMEOUT to (TransportResult.Failure(code, NetErrorCode.TIMEOUT) as DiagnosticResult)
        }

        is UnknownHostException -> NetErrorCode.DNS to (TransportResult.Failure(TransportErrorCode.DNS, NetErrorCode.DNS) as DiagnosticResult)
        is ConnectException -> NetErrorCode.CONNECTION_REFUSED to
            (TransportResult.Failure(TransportErrorCode.CONNECTION_REFUSED, NetErrorCode.CONNECTION_REFUSED) as DiagnosticResult)
        is SSLException -> NetErrorCode.TLS to (TransportResult.Failure(TransportErrorCode.TLS, NetErrorCode.TLS) as DiagnosticResult)
        is IOException -> NetErrorCode.IO to (TransportResult.Failure(TransportErrorCode.IO, NetErrorCode.IO) as DiagnosticResult)
        else -> NetErrorCode.UNKNOWN to (TransportResult.Failure(TransportErrorCode.IO, NetErrorCode.UNKNOWN) as DiagnosticResult)
    }
    NetDiagnostics.record(stage, result, record.durationMs)
    return NetResult.Error(code, record)
}