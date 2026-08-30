package com.rsplwe.esurfing.diagnostics

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Stable JSON Lines codec for [DiagnosticEvent].
 *
 * Every event is serialised as exactly one JSON object per line using ONLY the
 * fields in [WHITELIST] — serialisation is hand-built field by field, never
 * reflection, so an unknown/secret field can never slip into the output. All
 * whitelisted keys are always present (nulls written as JSON null), keeping the
 * schema stable and every line independently parseable.
 */
object DiagnosticEventJson {

    val WHITELIST: Set<String> = linkedSetOf(
        "runId", "attemptId", "correlationId", "stage", "timestamp", "durationMs",
        "layer", "outcome", "errorCode", "safeSummary", "truncated", "evidenceRef",
    )

    /**
     * Gson drops null-valued entries of a [JsonObject] unless serialization of
     * nulls is enabled — without this the whitelist guarantee breaks (nullable
     * fields such as `errorCode`/`evidenceRef` disappear from the line).
     */
    private val gson = GsonBuilder().serializeNulls().create()

    fun encode(event: DiagnosticEvent, sensitiveTerms: Collection<String> = emptyList()): String {
        val obj = JsonObject()
        obj.addProperty("runId", event.runId)
        obj.addProperty("attemptId", event.attemptId)
        obj.addProperty("correlationId", event.correlationId)
        obj.addProperty("stage", event.stage.wire)
        obj.addProperty("timestamp", event.timestampEpochMillis)
        addNullableLong(obj, "durationMs", event.durationMs)
        obj.addProperty("layer", event.result.layer.wire)
        obj.addProperty("outcome", event.result.outcome.wire)
        val errorCode = event.result.errorCode?.code
        if (errorCode == null) obj.add("errorCode", JsonNull.INSTANCE) else obj.addProperty("errorCode", errorCode)
        obj.addProperty("safeSummary", DiagnosticRedactor.redact(event.result.safeSummary, sensitiveTerms))
        obj.addProperty("truncated", event.truncated)
        if (event.evidenceRef == null) obj.add("evidenceRef", JsonNull.INSTANCE) else obj.addProperty("evidenceRef", event.evidenceRef)
        return gson.toJson(obj)
    }

    fun decode(line: String): DiagnosticEvent {
        val obj = try {
            JsonParser.parseString(line).asJsonObject
        } catch (e: Exception) {
            throw IllegalArgumentException("not a valid JSON object line", e)
        }
        val unknown = obj.keySet() - WHITELIST
        if (unknown.isNotEmpty()) throw IllegalArgumentException("unknown fields in diagnostic line: ${unknown.sorted()}")
        val missing = WHITELIST - obj.keySet()
        if (missing.isNotEmpty()) throw IllegalArgumentException("missing required fields in diagnostic line: ${missing.sorted()}")

        val stage = DiagnosticStage.fromWire(obj.get("stage").asString)
            ?: throw IllegalArgumentException("unknown stage: ${obj.get("stage").asString}")
        val layer = ResultLayer.fromWire(obj.get("layer").asString)
            ?: throw IllegalArgumentException("unknown layer: ${obj.get("layer").asString}")
        val outcome = ResultOutcome.fromWire(obj.get("outcome").asString)
            ?: throw IllegalArgumentException("unknown outcome: ${obj.get("outcome").asString}")
        val errorCode = obj.get("errorCode").takeUnless { it.isJsonNull }?.asString
        val safeSummary = obj.get("safeSummary").asString

        return DiagnosticEvent(
            runId = obj.get("runId").asString,
            attemptId = obj.get("attemptId").asString,
            correlationId = obj.get("correlationId").asString,
            stage = stage,
            timestampEpochMillis = obj.get("timestamp").asLong,
            durationMs = obj.get("durationMs").takeUnless { it.isJsonNull }?.asLong,
            result = reconstructResult(layer, outcome, errorCode, safeSummary),
            truncated = obj.get("truncated").asBoolean,
            evidenceRef = obj.get("evidenceRef").takeUnless { it.isJsonNull }?.asString,
        )
    }

    fun encodeLines(events: List<DiagnosticEvent>, sensitiveTerms: Collection<String> = emptyList()): String =
        events.joinToString("\n") { encode(it, sensitiveTerms) }

    fun decodeLines(text: String): List<DiagnosticEvent> =
        text.lineSequence().filter { it.isNotBlank() }.map { decode(it) }.toList()

    private fun reconstructResult(
        layer: ResultLayer,
        outcome: ResultOutcome,
        errorCode: String?,
        safeSummary: String,
    ): DiagnosticResult = when (layer) {
        ResultLayer.TRANSPORT -> when (outcome) {
            ResultOutcome.SUCCESS -> {
                requireNoErrorCode(errorCode, layer)
                TransportResult.Success
            }

            ResultOutcome.FAILURE -> TransportResult.Failure(transportCode(errorCode), safeSummary)
        }

        ResultLayer.HTTP -> when (outcome) {
            ResultOutcome.SUCCESS -> {
                requireNoErrorCode(errorCode, layer)
                HttpResult.Success
            }

            ResultOutcome.FAILURE -> HttpResult.Failure(httpCode(errorCode), safeSummary)
        }

        ResultLayer.PROTOCOL -> when (outcome) {
            ResultOutcome.SUCCESS -> {
                requireNoErrorCode(errorCode, layer)
                ProtocolResult.Success
            }

            ResultOutcome.FAILURE -> ProtocolResult.Failure(protocolCode(errorCode), safeSummary)
        }

        ResultLayer.BUSINESS -> when (outcome) {
            ResultOutcome.SUCCESS -> {
                requireNoErrorCode(errorCode, layer)
                BusinessResult.Success
            }

            ResultOutcome.FAILURE -> BusinessResult.Failure(businessCode(errorCode), safeSummary)
        }
    }

    private fun requireNoErrorCode(errorCode: String?, layer: ResultLayer) {
        if (errorCode != null) throw IllegalArgumentException("$layer success must not carry an errorCode")
    }

    private fun transportCode(errorCode: String?): TransportErrorCode =
        errorCode?.let { TransportErrorCode.fromCode(it) }
            ?: throw IllegalArgumentException("transport failure requires a valid errorCode: $errorCode")

    private fun httpCode(errorCode: String?): HttpErrorCode =
        errorCode?.let { HttpErrorCode.fromCode(it) }
            ?: throw IllegalArgumentException("http failure requires a valid errorCode: $errorCode")

    private fun protocolCode(errorCode: String?): ProtocolErrorCode =
        errorCode?.let { ProtocolErrorCode.fromCode(it) }
            ?: throw IllegalArgumentException("protocol failure requires a valid errorCode: $errorCode")

    private fun businessCode(errorCode: String?): BusinessErrorCode =
        errorCode?.let { BusinessErrorCode.fromCode(it) }
            ?: throw IllegalArgumentException("business failure requires a valid errorCode: $errorCode")

    private fun addNullableLong(obj: JsonObject, name: String, value: Long?) {
        if (value == null) obj.add(name, JsonNull.INSTANCE) else obj.addProperty(name, value)
    }
}
