package dev.anydm.engine.http

import kotlin.test.Test
import kotlin.test.assertEquals

class ResumeTest {
    @Test
    fun partialContentContinues() = assertEquals(ResumeDecision.Continue, resumeDecision(206, "bytes 100-199/200", 100))

    @Test
    fun partialContentAtWrongOffsetFails() = assertEquals(ResumeDecision.Fail(Reasons.CHANGED), resumeDecision(206, "bytes 0-199/200", 100))

    @Test
    fun partialContentWithoutHeaderContinues() = assertEquals(ResumeDecision.Continue, resumeDecision(206, null, 100))

    @Test
    fun okRestarts() = assertEquals(ResumeDecision.Restart, resumeDecision(200, null, 100))

    @Test
    fun rangeNotSatisfiableIsChanged() = assertEquals(ResumeDecision.Fail(Reasons.CHANGED), resumeDecision(416, null, 100))

    @Test
    fun errorsMapToHttp() {
        assertEquals(ResumeDecision.Fail("HTTP 404"), resumeDecision(404, null, 0))
        assertEquals(ResumeDecision.Fail("HTTP 503"), resumeDecision(503, null, 0))
    }

    @Test
    fun otherSuccessCodesFail() = assertEquals(ResumeDecision.Fail("HTTP 204"), resumeDecision(204, null, 0))
}
