package dev.anydm.engine.os

import dev.anydm.engine.Engine
import java.security.SecureRandom
import kotlin.random.asKotlinRandom
import kotlin.time.Clock

/** The bridge's logic with tokens from the system's cryptographic random source, as it is wired in the app. */
fun secureBridgeService(
    engine: Engine,
    clock: Clock = Clock.System,
): BridgeService = BridgeService(engine, clock, random = SecureRandom().asKotlinRandom())
