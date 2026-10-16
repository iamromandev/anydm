package dev.anydm.engine.core

import kotlin.random.Random

/** A Random whose [nextDouble] is always [value], so a jittered delay is exact in a test. Nothing else is supported. */
internal class FixedRandom(
    private val value: Double,
) : Random() {
    init {
        require(value >= 0.0 && value < 1.0) { "value must be in [0, 1): $value" }
    }

    override fun nextDouble(): Double = value

    override fun nextBits(bitCount: Int): Int = error("FixedRandom only answers nextDouble")
}
