package com.rsplwe.esurfing.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exhaustive assertions over [DiagnosticStage]: the exact contract stage set,
 * unique wire names, and from-wire round-trips.
 */
class DiagnosticEventStageTest {

    @Test
    fun stageSet_matchesContract() {
        val expected = listOf("portal", "session/zsm", "ticket", "login", "heartbeat", "term", "sms")
        assertEquals(expected, DiagnosticStage.entries.map { it.wire }, "stage wire names must match the contract exactly")
    }

    @Test
    fun stageWireNames_areUnique() {
        val wires = DiagnosticStage.entries.map { it.wire }
        assertEquals(wires.size, wires.distinct().size, "every stage must have a distinct wire name")
    }

    @Test
    fun everyStage_roundTripsThroughWireName() {
        for (stage in DiagnosticStage.entries) {
            val wire = stage.wire
            assertEquals(stage, DiagnosticStage.fromWire(wire), "fromWire('$wire') must resolve to $stage")
            assertNotNull(DiagnosticStage.fromWire(wire))
        }
    }

    @Test
    fun fromWire_rejectsUnknownName() {
        assertNull(DiagnosticStage.fromWire("bogus"))
        assertNull(DiagnosticStage.fromWire(""))
        assertNull(DiagnosticStage.fromWire("PORTAL"))
    }

    @Test
    fun everyStage_canBeMatchedExhaustively() {
        fun describe(stage: DiagnosticStage): String = when (stage) {
            DiagnosticStage.PORTAL -> "portal"
            DiagnosticStage.SESSION_ZSM -> "session/zsm"
            DiagnosticStage.TICKET -> "ticket"
            DiagnosticStage.LOGIN -> "login"
            DiagnosticStage.HEARTBEAT -> "heartbeat"
            DiagnosticStage.TERM -> "term"
            DiagnosticStage.SMS -> "sms"
        }
        assertTrue(DiagnosticStage.entries.all { describe(it).isNotBlank() })
    }
}
