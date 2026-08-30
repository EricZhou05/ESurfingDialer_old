package com.rsplwe.esurfing.diagnostics

/**
 * Stable machine-readable error codes. A code is a dotted identifier such as
 * `transport.connect_timeout` or `business.requires_sms`. Codes never carry
 * secret material — only a classification.
 */
interface ErrorCode {
    val code: String
}

enum class TransportErrorCode(override val code: String) : ErrorCode {
    DNS("transport.dns"),
    CONNECT_TIMEOUT("transport.connect_timeout"),
    CONNECTION_REFUSED("transport.connection_refused"),
    READ_TIMEOUT("transport.read_timeout"),
    WRITE_TIMEOUT("transport.write_timeout"),
    TLS("transport.tls"),
    IO("transport.io");

    companion object {
        fun fromCode(code: String): TransportErrorCode? = entries.firstOrNull { it.code == code }
    }
}

enum class HttpErrorCode(override val code: String) : ErrorCode {
    NON_2XX("http.non_2xx"),
    REDIRECT_LIMIT("http.redirect_limit"),
    MISSING_LOCATION("http.missing_location"),
    EMPTY_BODY("http.empty_body");

    companion object {
        fun fromCode(code: String): HttpErrorCode? = entries.firstOrNull { it.code == code }
    }
}

enum class ProtocolErrorCode(override val code: String) : ErrorCode {
    ZSM_MALFORMED("protocol.zsm_malformed"),
    UNKNOWN_ALGO_ID("protocol.unknown_algo_id"),
    DECRYPT("protocol.decrypt"),
    XML_MALFORMED("protocol.xml_malformed"),
    MISSING_FIELD("protocol.missing_field"),
    INVALID_VALUE("protocol.invalid_value");

    companion object {
        fun fromCode(code: String): ProtocolErrorCode? = entries.firstOrNull { it.code == code }
    }
}

enum class BusinessErrorCode(override val code: String) : ErrorCode {
    REJECTED("business.rejected"),
    REQUIRES_SMS("business.requires_sms"),
    SMS_QUERY_FAILED("business.sms_query_failed"),
    UNAUTHORIZED("business.unauthorized");

    companion object {
        fun fromCode(code: String): BusinessErrorCode? = entries.firstOrNull { it.code == code }
    }
}
