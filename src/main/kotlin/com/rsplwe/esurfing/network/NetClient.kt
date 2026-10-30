package com.rsplwe.esurfing.network

import com.rsplwe.esurfing.Constants
import com.rsplwe.esurfing.States
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import org.apache.commons.codec.digest.DigestUtils
import org.apache.log4j.Logger
import java.util.concurrent.TimeUnit

/**
 * Follows redirects manually (OkHttp's followRedirects is disabled so every hop
 * stays observable). Each hop's CDC headers (area/schoolid/domain) update
 * [States] and are captured into the per-call [RedirectHop] chain. Malformed
 * redirects surface as [RedirectProtocolException] so callers can classify
 * REDIRECT_LIMIT / MISSING_LOCATION instead of silently returning a 3xx.
 *
 * Secret discipline: only sanitised (query-free) URLs and non-secret CDC
 * headers are logged or captured — never the raw Location query or cookies.
 */
class RedirectInterceptor : Interceptor {

    private val logger: Logger = Logger.getLogger(RedirectInterceptor::class.java)

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        val hops = RedirectInterceptor.startChain()
        var response = chain.proceed(request)
        while (response.isRedirect) {
            if (hops.size >= Constants.MAX_REDIRECT_HOPS) {
                response.close()
                throw RedirectProtocolException(RedirectProtocolKind.REDIRECT_LIMIT)
            }

            val area = response.header("area")
            val schoolId = response.header("schoolid")
            val domain = response.header("domain")

            if (!area.isNullOrBlank()) {
                States.area = area
                logger.info("Redirect header -> CDC-Area: $area")
            }
            if (!schoolId.isNullOrBlank()) {
                States.schoolId = schoolId
                logger.info("Redirect header -> CDC-SchoolId: $schoolId")
            }
            if (!domain.isNullOrBlank()) {
                States.domain = domain
                logger.info("Redirect header -> CDC-Domain: $domain")
            }

            val location = response.header("Location")
            if (location.isNullOrBlank()) {
                response.close()
                throw RedirectProtocolException(RedirectProtocolKind.MISSING_LOCATION)
            }

            val safeTarget = safeUrlOf(location)
            logger.info(
                "Redirect #${hops.size + 1}: ${response.code} -> $safeTarget"
            )
            logger.debug(
                "Redirect #${hops.size + 1} raw Location: $location"
            )
            hops += RedirectHop(
                safeLocation = safeTarget,
                status = response.code,
                area = area,
                schoolId = schoolId,
                domain = domain,
            )

            val toClose = response
            // OkHttp keeps responseBodyOpen=true while a response body is unread.
            // Re-proceeding before closing the previous 302 throws
            // IllegalStateException("cannot make a new request because the previous
            // response is still open"), so the redirect-follow must close it first.
            request = request.newBuilder().url(location).build()
            toClose.close()
            response = chain.proceed(request)
        }
        return response
    }

    companion object {
        private val hopChain = ThreadLocal<MutableList<RedirectHop>>()

        private fun startChain(): MutableList<RedirectHop> {
            val hops = mutableListOf<RedirectHop>()
            hopChain.set(hops)
            return hops
        }

        /** Reads and clears the redirect chain captured for the current call. */
        fun takeChain(): List<RedirectHop> {
            val chain = hopChain.get()?.toList() ?: emptyList()
            hopChain.remove()
            return chain
        }
    }
}

fun createHttpClient(timeoutSeconds: Long = 10): OkHttpClient {
    val builder = OkHttpClient.Builder()
        .addInterceptor(RedirectInterceptor())
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
    return builder.build()
}

@Volatile
var apiClient = createHttpClient()

/**
 * Posts form-encoded [data] to [url] and returns a typed [NetResult].
 * This signature is stable (Todo 6 depends on it): non-2xx / redirect-limit /
 * empty-body / transport failures never fall into [NetResult.Success] and are
 * classified with a stable label in [NetResult.Error.exception].
 */
fun post(url: String, data: String, extraHeaders: HashMap<String, String> = HashMap()): NetResult<ResponseBody> {
    val type = "application/x-www-form-urlencoded".toMediaTypeOrNull()
    val body = data.toRequestBody(type)
    val request = Request.Builder()
        .removeHeader("User-Agent")
        .addHeader("User-Agent", Constants.USER_AGENT)
        .addHeader("Accept", Constants.REQUEST_ACCEPT)
        .addHeader("CDC-Checksum", DigestUtils.md5Hex(data))
        .addHeader("Client-ID", States.clientId)
        .addHeader("Algo-ID", States.algoId)
        .url(url)
        .post(body)

    extraHeaders.forEach {
        request.addHeader(it.key, it.value)
    }
    if (States.schoolId.isNotEmpty()) {
        request.addHeader("CDC-SchoolId", States.schoolId)
    }
    if (States.domain.isNotEmpty()) {
        request.addHeader("CDC-Domain", States.domain)
    }
    if (States.area.isNotEmpty()) {
        request.addHeader("CDC-Area", States.area)
    }

    val stage = NetDiagnostics.currentPostStage()
    val safeTarget = safeUrlOf(url)
    val startNanos = System.nanoTime()
    return try {
        val response = apiClient.newCall(request.build()).execute()
        val durationMs = elapsedMillis(startNanos)
        val chain = RedirectInterceptor.takeChain()
        val record = buildExchangeRecord(stage, safeTarget, response, durationMs, chain)
        classifyResponse(response, record)
    } catch (e: Throwable) {
        classifyFailure(e, stage, safeTarget, elapsedMillis(startNanos))
    }
}