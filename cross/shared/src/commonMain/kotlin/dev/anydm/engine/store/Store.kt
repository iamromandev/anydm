package dev.anydm.engine.store

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CoroutineDispatcher

/**
 * anydm's SQLite database (FND-13): downloads with everything that belongs to them, and settings (SET-01). Get one
 * from openStore (desktop). [driver] must already be at the current schema, with foreign keys on for every
 * connection. Every call runs on [io] as one transaction that never suspends, so pass a dispatcher that runs one task
 * at a time: SQLite has a single writer anyway.
 */
class Store internal constructor(
    private val driver: SqlDriver,
    io: CoroutineDispatcher,
) : AutoCloseable {
    private val database = createDatabase(driver)

    val downloads: DownloadStore = SqlDownloadStore(database, io)
    val settings: SettingsStore = SqlSettingsStore(database, io)

    /**
     * Closes the driver. With the JDBC driver on a file there is nothing held open between calls, so this changes
     * nothing there; an in-memory database is gone and every later call fails. It isn't serialized with calls in
     * flight, so close once nothing is calling.
     */
    override fun close() = driver.close()
}
