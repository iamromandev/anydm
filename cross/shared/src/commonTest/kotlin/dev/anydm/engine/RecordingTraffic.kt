package dev.anydm.engine

import dev.anydm.engine.net.TrafficBatch
import dev.anydm.engine.store.TrafficStore
import kotlinx.coroutines.CompletableDeferred

/** A [TrafficStore] that records each batch it was given (before passing it on) and can fail or hang on cue. */
internal class RecordingTraffic(
    private val inner: TrafficStore,
) : TrafficStore by inner {
    val adds = mutableListOf<TrafficBatch>()
    var failNext = 0
    var failAlways = false
    var gate: CompletableDeferred<Unit>? = null

    val bytes: Long get() = adds.sumOf { batch -> batch.days.values.sum() }

    override suspend fun add(
        batch: TrafficBatch,
        pruneBefore: Long,
    ) {
        if (failAlways) error("disk gone")
        if (failNext > 0) {
            failNext--
            error("disk hiccup")
        }
        adds += batch
        gate?.await()
        inner.add(batch, pruneBefore)
    }
}
