package dev.anydm.engine.platform

import kotlinx.coroutines.test.TestScope
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.asClock

/** Where [virtualClock] starts unless told otherwise: 2023-11-14T22:13:20Z. */
internal val virtualEpoch: Instant = Instant.fromEpochSeconds(1_700_000_000)

/**
 * Engine code takes a [Clock]; the app passes the system clock. In tests pass this one: it reads
 * the virtual time that `delay` and `testScheduler.advanceTimeBy` move, so timers and timestamps
 * can't disagree and nothing waits in real time. It reads [origin] when created.
 */
internal fun TestScope.virtualClock(origin: Instant = virtualEpoch): Clock = testScheduler.timeSource.asClock(origin)
