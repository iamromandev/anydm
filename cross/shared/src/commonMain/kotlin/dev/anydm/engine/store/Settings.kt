package dev.anydm.engine.store

import kotlinx.io.files.Path

/**
 * The engine's settings (SET-01). Stored one row per setting that differs from its default, so a better default in a
 * later version reaches everyone who never changed it. A new setting is a property here and a name in SqlSettingsStore;
 * it needs no migration. Renaming or re-encoding one does: that ships as SQL in a schema migration (see [SettingsStore]).
 */
data class Settings(
    /** How many downloads may transfer at once (FND-04). */
    val concurrencyLimit: Int = 3,
    /** Where downloads go when nothing else decides; null means the OS Downloads folder (#539 resolves it). */
    val defaultSaveFolder: Path? = null,
    /** Everything is paused, and stays paused after a restart (FND-05). */
    val globalPause: Boolean = false,
    /** How many automatic retries in a row a job gets before it fails (FND-07); 0 never retries. */
    val maxRetries: Int = 5,
    /** Write DEBUG lines to the log too (#556): every status move. */
    val debugLogging: Boolean = false,
    /** The most connections HTTP downloads open to one host, shared by all of them (HTTP-06). */
    val connectionsPerDownload: Int = 4,
) {
    init {
        require(concurrencyLimit >= 1) { "concurrencyLimit must be >= 1: $concurrencyLimit" }
        require(maxRetries >= 0) { "maxRetries must be >= 0: $maxRetries" }
        require(connectionsPerDownload in 1..16) { "connectionsPerDownload must be in 1..16: $connectionsPerDownload" }
        require(defaultSaveFolder == null || defaultSaveFolder.isAbsolute) {
            "defaultSaveFolder must be absolute: $defaultSaveFolder"
        }
    }
}
