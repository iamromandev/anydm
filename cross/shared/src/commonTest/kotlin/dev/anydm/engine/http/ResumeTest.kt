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

    @Test
    fun aStrongEtagIsTheIfRangeValidator() = assertEquals("\"v1\"", ifRangeOf("\"v1\"", "Wed, 21 Oct 2015 07:28:00 GMT"))

    @Test
    fun aWeakEtagNeverIsAnIfRangeValidatorSoTheLastModifiedIsUsed() =
        assertEquals("Wed, 21 Oct 2015 07:28:00 GMT", ifRangeOf("W/\"v1\"", "Wed, 21 Oct 2015 07:28:00 GMT"))

    @Test
    fun aWeakEtagAloneLeavesNoIfRange() = assertEquals(null, ifRangeOf("W/\"v1\"", null))

    @Test
    fun noValidatorLeavesNoIfRange() = assertEquals(null, ifRangeOf(null, null))

    @Test
    fun theLastModifiedIsUsedWithoutAnEtag() = assertEquals("x", ifRangeOf(null, "x"))
}
