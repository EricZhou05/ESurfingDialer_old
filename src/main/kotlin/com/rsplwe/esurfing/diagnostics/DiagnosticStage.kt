package com.rsplwe.esurfing.diagnostics

/**
 * The lifecycle stage an event was recorded at. Wire names match the contract
 * exactly (`portal`, `session/zsm`, `ticket`, `login`, `heartbeat`, `term`,
 * `sms`) and are what gets written to JSONL.
 */
enum class DiagnosticStage(val wire: String) {
    PORTAL("portal"),
    SESSION_ZSM("session/zsm"),
    TICKET("ticket"),
    LOGIN("login"),
    HEARTBEAT("heartbeat"),
    TERM("term"),
    SMS("sms");

    companion object {
        fun fromWire(wire: String): DiagnosticStage? = entries.firstOrNull { it.wire == wire }
    }
}
