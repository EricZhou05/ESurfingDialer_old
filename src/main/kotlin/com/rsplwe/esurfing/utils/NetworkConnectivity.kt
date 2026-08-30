package com.rsplwe.esurfing.utils

import cn.yescallop.fluenturi.Uri
import com.google.gson.Gson
import com.rsplwe.esurfing.Constants
import com.rsplwe.esurfing.States
import com.rsplwe.esurfing.diagnostics.BusinessErrorCode
import com.rsplwe.esurfing.diagnostics.BusinessResult
import com.rsplwe.esurfing.diagnostics.DiagnosticStage
import com.rsplwe.esurfing.diagnostics.HttpErrorCode
import com.rsplwe.esurfing.diagnostics.HttpResult
import com.rsplwe.esurfing.diagnostics.ProtocolErrorCode
import com.rsplwe.esurfing.diagnostics.ProtocolResult
import com.rsplwe.esurfing.model.RequireVerificate
import com.rsplwe.esurfing.model.ResponseRequireVerificate
import com.rsplwe.esurfing.network.NetDiagnostics
import com.rsplwe.esurfing.network.NetErrorCode
import com.rsplwe.esurfing.network.RedirectHop
import com.rsplwe.esurfing.network.RedirectInterceptor
import com.rsplwe.esurfing.network.RedirectProtocolException
import com.rsplwe.esurfing.network.apiClient
import com.rsplwe.esurfing.network.buildExchangeRecord
import com.rsplwe.esurfing.network.classifyFailure
import com.rsplwe.esurfing.network.createHttpClient
import com.rsplwe.esurfing.network.elapsedMillis
import com.rsplwe.esurfing.network.safeUrlOf
import com.rsplwe.esurfing.protocol.SmsQueryFailure
import com.rsplwe.esurfing.protocol.SmsVerifyOutcome
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.apache.commons.codec.digest.DigestUtils
import org.apache.log4j.Logger
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

enum class ConnectivityStatus {
    SUCCESS,
    REQUIRE_AUTHORIZATION,
    REQUEST_ERROR,
}

val captiveClient = createHttpClient()
val logger: Logger = Logger.getLogger("ConnectivityStatus")

/**
 * Probes the captive portal and returns whether the device is already
 * connected. Every classification is typed and recorded through
 * [NetDiagnostics]; the coarse [ConnectivityStatus] stays a three-value
 * contract for [com.rsplwe.esurfing.Client].
 *
 * HTTP status alone never proves connectivity: a 200 whose body carries no
 * historic portal marker (or is empty) is a typed portal-config failure and
 * returns REQUEST_ERROR, never SUCCESS.
 */
fun detectConfig(): ConnectivityStatus {
    val stage = DiagnosticStage.PORTAL
    val safeTarget = safeUrlOf(Constants.CAPTIVE_URL)
    val startNanos = System.nanoTime()
    val request = Request.Builder()
        .removeHeader("User-Agent")
        .addHeader("User-Agent", Constants.CAPTIVE_USER_AGENT)
        .addHeader("Accept", Constants.REQUEST_ACCEPT)
        .addHeader("Client-ID", States.clientId)
        .url(Constants.CAPTIVE_URL)
        .build()
    var response: Response? = null
    return try {
        response = captiveClient.newCall(request).execute()
        val durationMs = elapsedMillis(startNanos)
        val chain = RedirectInterceptor.takeChain()
        val record = buildExchangeRecord(stage, safeTarget, response, durationMs, chain)

        if (!response.isSuccessful) {
            NetDiagnostics.record(stage, HttpResult.Failure(HttpErrorCode.NON_2XX, "HTTP_${response.code}"), durationMs)
            logger.error("Portal detect failed: HTTP ${response.code}")
            ConnectivityStatus.REQUEST_ERROR
        } else if (response.body == null || response.body!!.contentLength() == 0L) {
            NetDiagnostics.record(stage, HttpResult.Failure(HttpErrorCode.EMPTY_BODY, "EMPTY_BODY"), durationMs)
            logger.error("Portal detect failed: EMPTY_BODY")
            ConnectivityStatus.REQUEST_ERROR
        } else {
            val content = response.body!!.string()
            response!!.close()
            response = null
            classifyPortalBody(content, chain, stage, durationMs)
        }
    } catch (e: Throwable) {
        classifyFailure(e, stage, safeTarget, elapsedMillis(startNanos))
        val label = if (e is RedirectProtocolException) e.kind.name else e.javaClass.simpleName
        logger.error("Portal detect failed: $label")
        ConnectivityStatus.REQUEST_ERROR
    } finally {
        response?.close()
    }
}

internal fun classifyPortalBody(
    content: String,
    chain: List<RedirectHop>,
    stage: DiagnosticStage,
    durationMs: Long,
): ConnectivityStatus {
    val portalConfig = content.extractBetweenTags(Constants.PORTAL_START_TAG, Constants.PORTAL_END_TAG)
    if (portalConfig.isEmpty()) {
        if (chain.any { isKnownPortalDomain(it.safeLocation) }) {
            NetDiagnostics.record(
                stage,
                ProtocolResult.Failure(ProtocolErrorCode.PORTAL_DETECTED_BY_DOMAIN, "portal_detected_by_known_domain"),
                durationMs,
            )
            logger.warn("Portal detect: known portal domain hit in redirect chain")
            return ConnectivityStatus.REQUIRE_AUTHORIZATION
        }
        NetDiagnostics.record(
            stage,
            ProtocolResult.Failure(ProtocolErrorCode.MISSING_FIELD, "portal_config_tag_missing"),
            durationMs,
        )
        logger.error("Portal detect failed: PORTAL_CONFIG_TAG_MISSING")
        return ConnectivityStatus.REQUEST_ERROR
    }

    val doc = Jsoup.parse(portalConfig, Parser.xmlParser())
    States.authUrl = doc.getElementsByTag("auth-url").first()?.text() ?: ""
    States.ticketUrl = doc.getElementsByTag("ticket-url").first()?.text() ?: ""

    States.extraCfgUrl = HashMap()
    doc.getElementsByTag("funcfg").first()?.children()?.forEach {
        if ((it.attribute("enable")?.value ?: "") == "1"
            && (it.attribute("url")?.value ?: "").isNotEmpty()
        ) {
            States.extraCfgUrl[it.tagName()] = it.attribute("url")!!.value
        }
    }
    if (States.authUrl.isEmpty() || States.ticketUrl.isEmpty()) {
        NetDiagnostics.record(
            stage,
            ProtocolResult.Failure(ProtocolErrorCode.MISSING_FIELD, "portal_config_missing_auth_or_ticket_url"),
            durationMs,
        )
        return ConnectivityStatus.REQUEST_ERROR
    }
    val uri = Uri.from(States.ticketUrl)
    val userIp = uri.queryParameters()["wlanuserip"]?.first()
    val acIp = uri.queryParameters()["wlanacip"]?.first()
    if (userIp.isNullOrEmpty() or acIp.isNullOrEmpty()) {
        NetDiagnostics.record(
            stage,
            ProtocolResult.Failure(ProtocolErrorCode.MISSING_FIELD, "portal_config_missing_userip_or_acip"),
            durationMs,
        )
        return ConnectivityStatus.REQUEST_ERROR
    }
    States.userIp = userIp!!
    States.acIp = acIp!!
    NetDiagnostics.record(stage, HttpResult.Success, durationMs)
    return ConnectivityStatus.REQUIRE_AUTHORIZATION
}

/**
 * Known China Telecom portal hosts. When the portal server answers through a
 * redirect chain targeted at one of these hosts but the body carries no
 * portal-config marker, the host name alone proves we landed on the captive
 * portal — so the probe must report REQUIRE_AUTHORIZATION instead of an
 * error. The list is intentionally small and versioned per contract.
 */
private val KNOWN_PORTAL_HOSTS = setOf("enet.10000.gd.cn")

fun isKnownPortalDomain(url: String): Boolean {
    val host = url.toHttpUrlOrNull()?.host ?: return false
    return KNOWN_PORTAL_HOSTS.any { host == it || host.endsWith(".$it") }
}

fun checkVerifyCodeStatus(username: String): SmsVerifyOutcome {
    return requestVerifyCode(username, "QueryVerificateCodeStatus", "11062000")
}

fun getVerifyCode(username: String): SmsVerifyOutcome {
    return requestVerifyCode(username, "QueryAuthCode", "0")
}

/**
 * Queries one SMS endpoint and returns a typed [SmsVerifyOutcome]. "Needs a
 * code" ([RequiresVerification]), "no code needed" ([NotRequired]) and "query
 * failed" ([QueryFailed]) are three distinct verdicts — never a collapsed
 * `false`. The response is closed in all paths and failures only record a
 * non-secret classification.
 */
fun requestVerifyCode(username: String, type: String, success: String): SmsVerifyOutcome {
    val url = States.extraCfgUrl[type]
    if (url.isNullOrEmpty()) {
        val summary = "sms_endpoint_missing:$type"
        NetDiagnostics.record(DiagnosticStage.SMS, BusinessResult.Failure(BusinessErrorCode.SMS_QUERY_FAILED, summary))
        return SmsVerifyOutcome.QueryFailed(SmsQueryFailure.ENDPOINT_MISSING, summary)
    }
    val currentTimeMillis = System.currentTimeMillis().toString()
    val body = Gson().toJson(
        RequireVerificate(
            schoolId = States.schoolId,
            username = username,
            timestamp = currentTimeMillis,
            authenticator = DigestUtils.md5Hex(States.schoolId + currentTimeMillis + Constants.AUTH_KEY).uppercase(),
        )
    ).toRequestBody("application/json".toMediaTypeOrNull())
    val request = Request.Builder()
        .removeHeader("User-Agent")
        .addHeader("User-Agent", Constants.USER_AGENT)
        .addHeader("Accept", "okhttp/3.4.1")
        .url(url)
        .post(body)

    val startNanos = System.nanoTime()
    return try {
        val response = apiClient.newCall(request.build()).execute()
        val durationMs = elapsedMillis(startNanos)
        try {
            if (!response.isSuccessful) {
                val summary = NetErrorCode.http(response.code)
                NetDiagnostics.record(
                    DiagnosticStage.SMS,
                    HttpResult.Failure(HttpErrorCode.NON_2XX, summary),
                    durationMs,
                )
                SmsVerifyOutcome.QueryFailed(SmsQueryFailure.HTTP_ERROR, summary)
            } else {
                classifySmsBody(response.body?.string(), success, durationMs)
            }
        } finally {
            response.close()
        }
    } catch (e: Throwable) {
        classifyFailure(e, DiagnosticStage.SMS, safeUrlOf(url), elapsedMillis(startNanos))
        val summary = when (e) {
            is java.net.SocketTimeoutException -> NetErrorCode.TIMEOUT
            is java.net.UnknownHostException -> NetErrorCode.DNS
            is java.net.ConnectException -> NetErrorCode.CONNECTION_REFUSED
            is java.io.IOException -> NetErrorCode.IO
            else -> NetErrorCode.UNKNOWN
        }
        logger.error("Portal SMS verify failed: $summary")
        SmsVerifyOutcome.QueryFailed(SmsQueryFailure.TRANSPORT, summary)
    }
}

private fun classifySmsBody(raw: String?, success: String, durationMs: Long): SmsVerifyOutcome {
    if (raw.isNullOrEmpty()) {
        val summary = "sms_empty_body"
        NetDiagnostics.record(DiagnosticStage.SMS, BusinessResult.Failure(BusinessErrorCode.SMS_QUERY_FAILED, summary), durationMs)
        return SmsVerifyOutcome.QueryFailed(SmsQueryFailure.MALFORMED_RESPONSE, summary)
    }
    val model = try {
        Gson().fromJson(raw, ResponseRequireVerificate::class.java)
    } catch (e: Exception) {
        null
    }
    val resCode = model?.resCode
    if (resCode == null) {
        val summary = "sms_response_malformed"
        NetDiagnostics.record(DiagnosticStage.SMS, BusinessResult.Failure(BusinessErrorCode.SMS_QUERY_FAILED, summary), durationMs)
        return SmsVerifyOutcome.QueryFailed(SmsQueryFailure.MALFORMED_RESPONSE, summary)
    }
    if (resCode == success) {
        NetDiagnostics.record(DiagnosticStage.SMS, BusinessResult.Failure(BusinessErrorCode.REQUIRES_SMS, "sms_requires_verification"), durationMs)
        return SmsVerifyOutcome.RequiresVerification
    }
    NetDiagnostics.record(DiagnosticStage.SMS, HttpResult.Success, durationMs)
    return SmsVerifyOutcome.NotRequired
}