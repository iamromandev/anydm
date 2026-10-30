package dev.anydm.engine.core

import dev.anydm.engine.core.ErrorClass.AUTH
import dev.anydm.engine.core.ErrorClass.GONE
import dev.anydm.engine.core.ErrorClass.TRANSIENT
import dev.anydm.engine.platform.virtualEpoch
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class MirrorChoiceTest {
    private val job = DownloadId(1)

    // Ids don't follow positions, and the list isn't in position order: only position decides.
    private val m0 = Mirror(MirrorId(12), job, "https://a.example/f", 0)
    private val m1 = Mirror(MirrorId(10), job, "https://b.example/f", 1)
    private val m2 = Mirror(MirrorId(11), job, "https://c.example/f", 2)
    private val mirrors = listOf(m2, m0, m1)

    private var nextId = 1L

    private fun ended(
        mirror: Mirror?,
        errorClass: ErrorClass?,
    ) = open(mirror).copy(endedAt = virtualEpoch, errorClass = errorClass, message = "failed")

    private fun ended(
        mirrorId: MirrorId?,
        errorClass: ErrorClass?,
    ) = Attempt(AttemptId(nextId++), job, virtualEpoch, mirrorId, virtualEpoch, errorClass, "failed")

    private fun open(mirror: Mirror?) = Attempt(AttemptId(nextId++), job, virtualEpoch, mirror?.id)

    @Test
    fun goneMirrorsAreSkippedInPositionOrder() {
        val one = listOf(ended(m0, GONE))
        nextMirror(mirrors, one) shouldBe m1
        mirrorToStart(mirrors, one) shouldBe m1

        val two = one + ended(m1, GONE)
        nextMirror(mirrors, two) shouldBe m2

        val three = two + ended(m2, GONE)
        nextMirror(mirrors, three) shouldBe null
        mirrorToStart(mirrors, three) shouldBe m0
    }

    @Test
    fun aNewAttemptAfterEveryMirrorWasGoneStartsANewRound() {
        val allGone = listOf(ended(m0, GONE), ended(m1, GONE), ended(m2, GONE))

        goneMirrors(mirrors, allGone + open(m0)).shouldBeEmpty()
        goneMirrors(mirrors, allGone + ended(m0, GONE)) shouldBe setOf(m0.id)
    }

    @Test
    fun onlyGoneSkipsAMirror() {
        val attempts = listOf(ended(m0, TRANSIENT), ended(m0, AUTH), ended(m0, null))

        mirrorToStart(mirrors, attempts) shouldBe m0
        nextMirror(mirrors, attempts) shouldBe m0
    }

    @Test
    fun nullAndUnknownMirrorIdsChangeNothing() {
        goneMirrors(mirrors, listOf(ended(null as MirrorId?, GONE), ended(MirrorId(99), GONE))).shouldBeEmpty()
    }

    @Test
    fun noMirrorsGivesNull() {
        val attempts = listOf(ended(m0, GONE))

        mirrorToStart(emptyList(), attempts) shouldBe null
        nextMirror(emptyList(), attempts) shouldBe null
    }
}
