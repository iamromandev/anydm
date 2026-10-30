package dev.anydm.engine.platform

import kotlinx.coroutines.delay
import kotlin.time.Duration

/** Answers from [answers] by network key (null when unknown) after [delay] of virtual time; [calls] has the keys asked. */
internal class FakeMeteredDetector(
    val answers: MutableMap<String, Boolean?> = mutableMapOf(),
    var delay: Duration = Duration.ZERO,
) : MeteredDetector {
    val calls = mutableListOf<String>()

    override suspend fun isMetered(network: String): Boolean? {
        calls += network
        delay(delay)
        return answers[network]
    }
}
