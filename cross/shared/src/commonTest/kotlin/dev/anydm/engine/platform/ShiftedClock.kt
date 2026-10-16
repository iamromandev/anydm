package dev.anydm.engine.platform

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/** [base] moved by [offset], which a test changes to set the wall clock forward or back. */
internal class ShiftedClock(
    private val base: Clock,
    var offset: Duration = Duration.ZERO,
) : Clock {
    override fun now(): Instant = base.now() + offset
}
