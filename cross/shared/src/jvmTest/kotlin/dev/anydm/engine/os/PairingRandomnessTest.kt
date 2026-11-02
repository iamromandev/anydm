package dev.anydm.engine.os

import io.kotest.matchers.types.shouldBeInstanceOf
import java.security.SecureRandom
import kotlin.random.Random
import kotlin.random.asJavaRandom
import kotlin.test.Test
import kotlin.time.Clock

// The source a pairing draws from. It is private on purpose, so this reads it where nobody else may.
internal fun randomOf(pairing: BridgePairing): Random =
    BridgePairing::class.java
        .getDeclaredField("random")
        .apply { isAccessible = true }
        .get(pairing) as Random

/** Codes, nonces and ids must come from a cryptographic source unless a test says otherwise. */
class PairingRandomnessTest {
    @Test
    fun secureRandomIsTheJdksSecureRandom() {
        secureRandom().asJavaRandom().shouldBeInstanceOf<SecureRandom>()
    }

    @Test
    fun aPairingDrawsFromTheSecureSourceUnlessToldOtherwise() {
        randomOf(BridgePairing(InMemoryPairStore(), Clock.System)).asJavaRandom().shouldBeInstanceOf<SecureRandom>()
    }
}
