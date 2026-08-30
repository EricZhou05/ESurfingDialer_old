package com.rsplwe.esurfing.diagnostics

/** Which of the four typed result layers an outcome belongs to. */
enum class ResultLayer(val wire: String) {
    TRANSPORT("transport"),
    HTTP("http"),
    PROTOCOL("protocol"),
    BUSINESS("business");

    companion object {
        fun fromWire(wire: String): ResultLayer? = entries.firstOrNull { it.wire == wire }
    }
}

/** Coarse outcome classification shared by every layer. */
enum class ResultOutcome(val wire: String) {
    SUCCESS("success"),
    FAILURE("failure");

    companion object {
        fun fromWire(wire: String): ResultOutcome? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * A typed, layer-tagged outcome. Every branch carries its own stable error code
 * (null on success) plus a *safe summary* — classification and sanitised detail
 * only. Never place passwords, SMS codes, tickets, cookies, keys, request
 * bodies, full URLs or exception stack traces into [safeSummary].
 */
sealed interface DiagnosticResult {
    val layer: ResultLayer
    val outcome: ResultOutcome
    val errorCode: ErrorCode?
    val safeSummary: String
}

/** Transport / I/O level outcomes. */
sealed interface TransportResult : DiagnosticResult {
    override val layer: ResultLayer get() = ResultLayer.TRANSPORT

    object Success : TransportResult {
        override val outcome: ResultOutcome get() = ResultOutcome.SUCCESS
        override val errorCode: ErrorCode? get() = null
        override val safeSummary: String get() = "transport ok"
    }

    data class Failure(
        override val errorCode: TransportErrorCode,
        override val safeSummary: String,
    ) : TransportResult {
        override val outcome: ResultOutcome get() = ResultOutcome.FAILURE
    }
}

/** HTTP / redirect / body-shape level outcomes. */
sealed interface HttpResult : DiagnosticResult {
    override val layer: ResultLayer get() = ResultLayer.HTTP

    object Success : HttpResult {
        override val outcome: ResultOutcome get() = ResultOutcome.SUCCESS
        override val errorCode: ErrorCode? get() = null
        override val safeSummary: String get() = "http ok"
    }

    data class Failure(
        override val errorCode: HttpErrorCode,
        override val safeSummary: String,
    ) : HttpResult {
        override val outcome: ResultOutcome get() = ResultOutcome.FAILURE
    }
}

/** Protocol / parsing / decryption level outcomes. */
sealed interface ProtocolResult : DiagnosticResult {
    override val layer: ResultLayer get() = ResultLayer.PROTOCOL

    object Success : ProtocolResult {
        override val outcome: ResultOutcome get() = ResultOutcome.SUCCESS
        override val errorCode: ErrorCode? get() = null
        override val safeSummary: String get() = "protocol ok"
    }

    data class Failure(
        override val errorCode: ProtocolErrorCode,
        override val safeSummary: String,
    ) : ProtocolResult {
        override val outcome: ResultOutcome get() = ResultOutcome.FAILURE
    }
}

/** Business / application-level outcomes. */
sealed interface BusinessResult : DiagnosticResult {
    override val layer: ResultLayer get() = ResultLayer.BUSINESS

    object Success : BusinessResult {
        override val outcome: ResultOutcome get() = ResultOutcome.SUCCESS
        override val errorCode: ErrorCode? get() = null
        override val safeSummary: String get() = "business ok"
    }

    data class Failure(
        override val errorCode: BusinessErrorCode,
        override val safeSummary: String,
    ) : BusinessResult {
        override val outcome: ResultOutcome get() = ResultOutcome.FAILURE
    }
}
