package dev.anydm.engine.core

import dev.anydm.engine.protocol.ProbeResult

/** How [StepRunner.prepare] ended. */
sealed interface PrepareOutcome {
    val download: Download

    /**
     * Probed and QUEUED; the queue keeps it until a slot frees, then passes it to StepRunner.run. A Ready lives in
     * memory only (the probe result is not persisted). A QUEUED job without its Ready, after a restart or a PAUSED to
     * QUEUED move, has nothing to run with: it must go back through PENDING and [StepRunner.prepare]. That re-probe is
     * also where a protocol checks that the source is still the one it fetched (an ETag or size check, #538).
     */
    data class Ready(
        override val download: Download,
        val mirror: Mirror,
        val probe: ProbeResult,
        /** The job's other mirrors not gone this round, for Transfer.mirrors. */
        val others: List<Mirror> = emptyList(),
    ) : PrepareOutcome

    data class Failed(
        override val download: Download,
        val failure: StepFailure,
    ) : PrepareOutcome
}
