package dev.anydm.engine.store

/**
 * The engine's [Settings], kept in the same versioned database as everything else: a setting that is renamed or
 * stored differently in a later version ships as SQL in a schema migration.
 *
 * Only values that differ from their default are stored. A user who explicitly chose what is today's default
 * therefore follows a later change of that default. A stored value this version can't read falls back to its
 * default instead of stopping the engine from starting (logging it arrives with #556).
 *
 * Cancellation: an [update] that has been asked for is made even if the caller is cancelled, though the caller may
 * lose the return value.
 */
interface SettingsStore {
    /** The stored settings; one with no row, or a value this version can't read, has its default. */
    suspend fun load(): Settings

    /**
     * Applies [change] to the stored settings in one transaction and returns what was stored. [change] runs on the
     * store's IO thread, inside the transaction: it must be quick and must not call the store.
     */
    suspend fun update(change: (Settings) -> Settings): Settings
}
