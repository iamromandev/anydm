package dev.anydm.engine.os

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** Recognising the hotkey from key events (OSI-06), without any real key capture. */
class ChordDetectorTest {
    private val chord = Chord(setOf(Key.SHIFT, Key.CTRL), Key.D)

    private fun detector() = ChordDetector(chord)

    private fun ChordDetector.down(key: Key) = feed(key, down = true)

    private fun ChordDetector.up(key: Key) = feed(key, down = false)

    @Test
    fun theKeyWithTheModifiersHeldFiresOnce() {
        val d = detector()
        d.down(Key.CTRL) shouldBe false
        d.down(Key.SHIFT) shouldBe false
        d.down(Key.D) shouldBe true
    }

    @Test
    fun theOrderOfTheModifiersDoesNotMatter() {
        val d = detector()
        d.down(Key.SHIFT)
        d.down(Key.CTRL)
        d.down(Key.D) shouldBe true
    }

    @Test
    fun aHeldKeyRepeatingFiresOnlyOnce() {
        val d = detector()
        d.down(Key.CTRL)
        d.down(Key.SHIFT)
        d.down(Key.D) shouldBe true
        repeat(20) { d.down(Key.D) shouldBe false }
    }

    @Test
    fun releasingAndPressingAgainFiresAgain() {
        val d = detector()
        d.down(Key.CTRL)
        d.down(Key.SHIFT)
        d.down(Key.D) shouldBe true
        d.up(Key.D)
        d.down(Key.D) shouldBe true
    }

    @Test
    fun lettingGoOfAModifierAndPressingItAgainWhileTheKeyIsHeldDoesNotRefire() {
        val d = detector()
        d.down(Key.CTRL)
        d.down(Key.SHIFT)
        d.down(Key.D) shouldBe true
        d.up(Key.SHIFT)
        d.down(Key.SHIFT) shouldBe false
        d.down(Key.D) shouldBe false
    }

    @Test
    fun theKeyAloneOrWithOneModifierDoesNothing() {
        val d = detector()
        d.down(Key.D) shouldBe false
        d.up(Key.D)
        d.down(Key.CTRL)
        d.down(Key.D) shouldBe false
        d.up(Key.D)
        d.up(Key.CTRL)
        d.down(Key.SHIFT)
        d.down(Key.D) shouldBe false
    }

    @Test
    fun aModifierLetGoOfBeforeTheKeyDoesNotCount() {
        val d = detector()
        d.down(Key.CTRL)
        d.down(Key.SHIFT)
        d.up(Key.SHIFT)
        d.down(Key.D) shouldBe false
    }

    @Test
    fun theKeyRepeatingWhileTheModifiersAreAddedDoesNotFire() {
        val d = detector()
        d.down(Key.D)
        repeat(5) { d.down(Key.D) }
        d.down(Key.CTRL)
        d.down(Key.SHIFT)
        d.down(Key.D) shouldBe false
        d.up(Key.D)
        d.down(Key.D) shouldBe true
    }

    @Test
    fun theKeyBeforeTheModifiersDoesNotFireWhenTheyComeAfter() {
        val d = detector()
        d.down(Key.D)
        d.down(Key.CTRL) shouldBe false
        d.down(Key.SHIFT) shouldBe false
    }

    @Test
    fun theOtherPlatformsModifierIsNotThisOnes() {
        val mac = ChordDetector(Chord(setOf(Key.SHIFT, Key.META), Key.D))
        mac.down(Key.SHIFT)
        mac.down(Key.CTRL)
        mac.down(Key.D) shouldBe false
        mac.up(Key.D)
        mac.down(Key.META)
        mac.down(Key.D) shouldBe true
    }

    @Test
    fun resettingForgetsWhatWasHeld() {
        val d = detector()
        d.down(Key.CTRL)
        d.down(Key.SHIFT)
        d.reset()
        d.down(Key.D) shouldBe false
    }
}
