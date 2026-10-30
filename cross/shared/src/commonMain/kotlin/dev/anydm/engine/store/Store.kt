package dev.anydm.engine.store

import app.cash.sqldelight.db.SqlDriver
import dev.anydm.engine.log.Log
import kotlinx.coroutines.CoroutineDispatcher

/**
 * anydm's SQLite database (FND-13): downloads with everything that belongs to them, settings (SET-01), traffic (NET-04), categories (FIL-01) the history of finished downloads (FIL-10) the installed runtime tools (BIN-01) and the saved proxies (NET-15). Get one
 * from openStore (desktop). [driver] must already be at the current schema, with foreign keys on for every
 * connection. Every call runs on [io] as one transaction that never suspends, so pass a dispatcher that runs one task
 * at a time: SQLite has a single writer anyway.
 */
class Store internal constructor(
    private val driver: SqlDriver,
    io: CoroutineDispatcher,
    log: Log = Log.None,
    /** Where the cookies of downloads are kept (OSI-03); by default memory only, which is what a test or a machine with no keychain gets. */
    val cookies: CookieVault = CookieVault(null),
) : AutoCloseable {
    private val database = createDatabase(driver)

    private val sqlDownloads = SqlDownloadStore(database, io, cookies)
    val downloads: DownloadStore = sqlDownloads
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
    val rules: RuleStore = SqlRuleStore(database, io, log)
    val proxies: ProxyStore = SqlProxyStore(database, io)

    /**
     * Moves cookies stored before they were sealed in the keychain into it (OSI-03), once the keychain has them. Returns how
     * many it moved; the others are tried again at the next start.
     */
    suspend fun sealLegacyCookies(): Int = sqlDownloads.sealLegacyCookies()

    /**
     * Closes the driver. With the JDBC driver on a file there is nothing held open between calls, so this changes
     * nothing there; an in-memory database is gone and every later call fails. It isn't serialized with calls in
     * flight, so close once nothing is calling.
     */
    override fun close() = driver.close()
}
