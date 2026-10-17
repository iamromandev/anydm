package dev.anydm.engine.store

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.anydm.engine.log.Log
import dev.anydm.engine.platform.AppDirs
import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.platform.FileSystem
import dev.anydm.engine.store.db.AnydmDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import java.util.Properties

/** The database's name in the data folder. */
internal const val DATABASE_FILE = "anydm.db"

/**
 * Opens anydm's database, anydm.db in [AppDirs.data], creating the folder and the file or upgrading an older file.
 * Which folder that is (Roaming or Local on Windows) is AppDirs' business.
 *
 * Failures come back through three channels:
 * - [FileResult.Failed]: the data folder can't be used (it is a file, no permission, disk full).
 * - [IncompatibleStoreException]: the file isn't anydm's to open (a database from a newer anydm, a database that
 *   holds other tables, a migration that left rows without their parents), or it holds a value this anydm doesn't
 *   know (an enum name a newer anydm wrote; thrown by the call that reads it). Nothing was changed.
 * - The driver's own exception (a `java.sql.SQLException`): the file isn't a database, the disk filled up while
 *   upgrading, the file is read-only or locked by another process.
 */
@OptIn(ExperimentalCoroutinesApi::class)
suspend fun openStore(
    appDirs: AppDirs,
    fileSystem: FileSystem,
    log: Log = Log.None,
): FileResult<Store> {
    val folder = fileSystem.prepareFolder(appDirs.data)
    if (folder is FileResult.Failed) return folder
    // One call at a time: SQLite has one writer, and our own connections then never wait on each other.
    val io = Dispatchers.IO.limitedParallelism(1)
    val driver = withContext(io) { openDatabaseFile(Path(appDirs.data, DATABASE_FILE)) }
    return FileResult.Ok(Store(driver, io, log))
}

internal fun openDatabaseFile(
    file: Path,
    schema: SqlSchema<QueryResult.Value<Unit>> = AnydmDb.Schema,
): SqlDriver {
    require(file.isAbsolute) { "path must be absolute: $file" }
    // The path goes into a JDBC URL, where these would turn it into another database or other options.
    require('?' !in file.toString() && "mode=memory" !in file.toString()) { "path must not contain '?' or 'mode=memory': $file" }
    return openJdbcDatabase("jdbc:sqlite:$file", schema)
}

/**
 * Opens [url] at the schema's version. The upgrade runs on a driver without foreign keys, then closes (for a file),
 * and the driver that comes back has them on: SQLite's table rebuild (create, copy, drop, rename) deletes through
 * the foreign keys of a table it drops, and `PRAGMA foreign_keys = OFF` does nothing inside a transaction, so the
 * migrations need a connection that never had them on.
 *
 * For a file this driver opens a connection per thread, and per statement outside a transaction, so per-connection
 * settings go in the properties: a one-off PRAGMA would be lost. The journal stays SQLite's default (DELETE): WAL
 * would be checkpointed and dropped at every close here, and would leave -wal/-shm files beside the database.
 */
internal fun openJdbcDatabase(
    url: String,
    schema: SqlSchema<QueryResult.Value<Unit>> = AnydmDb.Schema,
): SqlDriver {
    if (url == JdbcSqliteDriver.IN_MEMORY) {
        // The one connection is the database: it can't be closed and reopened, so foreign keys go on after the upgrade.
        val driver = JdbcSqliteDriver(url, Properties())
        try {
            upgrade(driver, schema)
            driver.execute(null, "PRAGMA foreign_keys = ON", 0).value
        } catch (e: Exception) {
            driver.close()
            throw e
        }
        return driver
    }
    JdbcSqliteDriver(url, Properties()).use { upgrade(it, schema) }
    return JdbcSqliteDriver(url, Properties().apply { setProperty("foreign_keys", "true") })
}

/**
 * Creates an empty database or migrates an older one, in one transaction that also sets the version. The driver must
 * have foreign keys off. Refuses, and leaves the file as it is, a newer database, an unversioned one that already has
 * tables, and a migration whose result has rows without their parents.
 */
private fun upgrade(
    driver: SqlDriver,
    schema: SqlSchema<QueryResult.Value<Unit>>,
) {
    object : TransacterImpl(driver) {}.transaction {
        val version = driver.longs("PRAGMA user_version").singleOrNull() ?: 0L
        if (version > schema.version) {
            throw IncompatibleStoreException("The database is at schema version $version; this anydm reads up to ${schema.version}")
        }
        if (version == schema.version) return@transaction
        if (version == 0L) {
            // Not ours: creating tables beside someone else's would claim the file.
            if (driver.longs("SELECT count(*) FROM sqlite_master WHERE substr(name, 1, 7) <> 'sqlite_'").single() != 0L) {
                throw IncompatibleStoreException("The database has tables but no schema version; it isn't an anydm database")
            }
            schema.create(driver).value
        } else {
            schema.migrate(driver, version, schema.version).value
        }
        driver.execute(null, "PRAGMA user_version = ${schema.version}", 0).value
        // Deleting through a table with foreign keys off cascades nothing: look for what that left behind, now,
        // while rolling back is still possible.
        val orphans = driver.rowCount("PRAGMA foreign_key_check")
        if (orphans > 0) {
            throw IncompatibleStoreException("The migration to schema version ${schema.version} left $orphans rows without their parent")
        }
    }
}

private fun SqlDriver.rowCount(sql: String): Int =
    executeQuery(
        null,
        sql,
        { c -> QueryResult.Value(generateSequence { if (c.next().value) 1 else null }.count()) },
        0,
    ).value

// The first column of every row.
private fun SqlDriver.longs(sql: String): List<Long?> =
    executeQuery(
        null,
        sql,
        { c -> QueryResult.Value(buildList { while (c.next().value) add(c.getLong(0)) }) },
        0,
    ).value
