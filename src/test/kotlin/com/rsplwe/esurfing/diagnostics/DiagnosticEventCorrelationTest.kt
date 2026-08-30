package com.rsplwe.esurfing.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Correlation contract: events from the same attempt share run/attempt/
 * correlation IDs; different runs get different runIds; separate attempts in
 * one run get distinct attemptIds but share the runId.
 */
class DiagnosticEventCorrelationTest {

    @Test
    fun eventsFromSameAttempt_shareAllThreeIds() {
        val ctx = DiagnosticContext.start()
        val portal = ctx.event(DiagnosticStage.PORTAL, TransportResult.Success)
        val login = ctx.event(DiagnosticStage.LOGIN, BusinessResult.Success)

        assertEquals(ctx.runId, portal.runId)
        assertEquals(ctx.runId, login.runId)
        assertEquals(ctx.attemptId, portal.attemptId)
        assertEquals(ctx.attemptId, login.attemptId)
        assertEquals(ctx.correlationId, portal.correlationId)
        assertEquals(ctx.correlationId, login.correlationId)
    }

    @Test
    fun differentRuns_haveDifferentRunIds() {
        val a = DiagnosticContext.start()
        val b = DiagnosticContext.start()
        assertNotEquals(a.runId, b.runId)
    }

    @Test
    fun attemptsInSameRun_shareRunIdButDifferInAttemptAndCorrelation() {
        val runId = DiagnosticIds.newRunId()
        val a = DiagnosticContext.startAttempt(runId)
        val b = DiagnosticContext.startAttempt(runId)

        assertEquals(runId, a.runId)
        assertEquals(runId, b.runId)
        assertNotEquals(a.attemptId, b.attemptId)
        assertNotEquals(a.correlationId, b.correlationId)
    }

    @Test
    fun generatedIds_areUuidShapedAndUnique() {
        val runIds = List(64) { DiagnosticIds.newRunId() }
        val attemptIds = List(64) { DiagnosticIds.newAttemptId() }
        val correlationIds = List(64) { DiagnosticIds.newCorrelationId() }
        assertTrue(runIds.all { it.matches(uuidPattern) })
        assertTrue(attemptIds.all { it.matches(uuidPattern) })
        assertTrue(correlationIds.all { it.matches(uuidPattern) })
        assertEquals(runIds.size, runIds.distinct().size)
        assertEquals(attemptIds.size, attemptIds.distinct().size)
        assertEquals(correlationIds.size, correlationIds.distinct().size)
    }

    private companion object {
        val uuidPattern = Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")
    }
}
