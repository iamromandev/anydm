package dev.anydm.engine.os

import dev.anydm.engine.Engine
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.io.IOException

/** The browsers listed; fails the test if the pairings could not be read. */
internal suspend fun BridgePairing.listed(): List<PairedBrowser> = browsers().shouldBeInstanceOf<PairedBrowsers.Listed>().browsers

/** The browsers the engine lists; fails the test if the pairings could not be read. */
internal suspend fun Engine.listedBrowsers(): List<PairedBrowser> = pairedBrowsers().shouldBeInstanceOf<PairedBrowsers.Listed>().browsers

/**
 * A store whose file can't be read at the moment ([failLoad]): `load` throws, as [PairStore] says it does when pairings may be
 * there but can't be read. It counts its loads and saves.
 */
internal class FlakyPairStore(
    initial: List<PairRecord>,
) : PairStore {
    var records = initial
    var failLoad = true
    var loads = 0
    var saves = 0

    override fun load(): List<PairRecord> {
        loads++
        if (failLoad) throw IOException("could not read")
        return records
    }

    override fun save(records: List<PairRecord>) {
        saves++
        this.records = records.toList()
    }
}
