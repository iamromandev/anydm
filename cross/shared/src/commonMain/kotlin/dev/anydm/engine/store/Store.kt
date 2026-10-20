package dev.anydm.engine.store

import app.cash.sqldelight.db.SqlDriver
import dev.anydm.engine.log.Log
import kotlinx.coroutines.CoroutineDispatcher

/**
 * anydm's SQLite database (FND-13): downloads with everything that belongs to them, settings (SET-01), traffic (NET-04), categories (FIL-01) the history of finished downloads (FIL-10) and the installed runtime tools (BIN-01). Get one
 * from openStore (desktop). [driver] must already be at the current schema, with foreign keys on for every
 * connection. Every call runs on [io] as one transaction that never suspends, so pass a dispatcher that runs one task
 * at a time: SQLite has a single writer anyway.
 */
class Store internal constructor(
    private val driver: SqlDriver,
    io: CoroutineDispatcher,
    log: Log = Log.None,
) : AutoCloseable {
    private val database = createDatabase(driver)

    val downloads: DownloadStore = SqlDownloadStore(database, io)
    val settings: SettingsStore = SqlSettingsStore(database, io, log)
    val traffic: TrafficStore = SqlTrafficStore(database, io)
    val categories: CategoryStore = SqlCategoryStore(database, io)
    val history: HistoryStore = SqlHistoryStore(database, io)
    val tools: ToolStore = SqlToolStore(database, io)
    val extractions: ExtractionStore = SqlExtractionStore(database, io)
    val collectionItems: CollectionItemStore = SqlCollectionItemStore(database, io)
    val profiles: ProfileStore = SqlProfileStore(database, io)
    val torrents: TorrentStore = SqlTorrentStore(database, io)
    val hostKeys: HostKeyStore = SqlHostKeyStore(database, io)

    /**
     * Closes the driver. With the JDBC driver on a file there is nothing held open between calls, so this changes
     * nothing there; an in-memory database is gone and every later call fails. It isn't serialized with calls in
     * flight, so close once nothing is calling.
     */
    override fun close() = driver.close()
}
