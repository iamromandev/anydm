package dev.anydm.engine.os

import kotlin.time.Instant

/**
 * A browser the user paired: its [id], a [label] for people, and the [secret] both sides hold (32 bytes as hex). The secret is
 * what makes the browser trusted, so it is never printed.
 */
class PairRecord(
    val id: String,
    val label: String,
    val secret: String,
    val createdAt: Instant,
    val lastUsed: Instant,
) {
    fun copy(
        label: String = this.label,
        lastUsed: Instant = this.lastUsed,
    ) = PairRecord(id, label, secret, createdAt, lastUsed)

    override fun toString(): String = "PairRecord(id=$id, label=$label, createdAt=$createdAt, lastUsed=$lastUsed)"
}

/**
 * Where the pairings are kept between runs. [load] answers what is there: nothing stored is no pairings, and so is a store
 * that is damaged for good (it is the store's business to keep the damaged copy aside), which only costs the user a new
 * pairing. It throws `kotlinx.io.IOException` when pairings may be there but can't be read now (a read error, a folder that
 * can't be entered, a file of a version this code does not know): that is not "none", and whoever called must not write
 * anything over them, but try again later (the app says "store unavailable" to the browser and the screen meanwhile). [save]
 * replaces everything and throws `kotlinx.io.IOException` if it could not be done, so a pairing is never confirmed to a
 * browser that would be forgotten.
 */
interface PairStore {
    fun load(): List<PairRecord>

    fun save(records: List<PairRecord>)
}

/** Keeps the pairings in memory only: for tests, and for an engine that has no folder for them. */
class InMemoryPairStore(
    initial: List<PairRecord> = emptyList(),
) : PairStore {
    private var records = initial

    override fun load(): List<PairRecord> = records

    override fun save(records: List<PairRecord>) {
        this.records = records.toList()
    }
}
