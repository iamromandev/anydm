package dev.anydm.engine.store

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.anydm.engine.core.CategoryId
import dev.anydm.engine.core.CollectionKind
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.ProfileId
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.az
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.printableAscii
import io.kotest.property.arbitrary.string
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlin.test.AfterTest
import kotlin.time.Instant

// Raw SQL through any driver, for checks that must bypass the repositories.
internal fun SqlDriver.exec(sql: String) {
    execute(null, sql, 0).value
}

internal fun SqlDriver.longs(sql: String): List<Long?> =
    executeQuery(
        null,
        sql,
        { c -> QueryResult.Value(buildList { while (c.next().value) add(c.getLong(0)) }) },
        0,
    ).value

internal fun SqlDriver.texts(sql: String): List<String?> =
    executeQuery(
        null,
        sql,
        { c -> QueryResult.Value(buildList { while (c.next().value) add(c.getString(0)) }) },
        0,
    ).value

internal fun SqlDriver.userVersion(): Long = longs("PRAGMA user_version").single() ?: 0L

internal fun SqlDriver.count(table: String): Long = longs("SELECT count(*) FROM $table").single() ?: 0L

/** Each test gets a new database from [newDriver]; subclasses pick the driver (in memory, a file, later Android). */
abstract class StoreTestBase {
    /** A new, empty database at the current schema. */
    protected abstract fun newDriver(): SqlDriver

    /** Runs after the driver is closed, for example to delete a temporary folder. */
    protected open fun cleanUp() {}

    protected lateinit var driver: SqlDriver
        private set

    protected fun TestScope.newStore(cookies: CookieVault = CookieVault(null)): Store {
        driver = newDriver()
        return Store(driver, StandardTestDispatcher(testScheduler), cookies = cookies)
    }

    @AfterTest
    fun closeDatabase() {
        if (::driver.isInitialized) driver.close()
        cleanUp()
    }
}

internal val arbNewDownload: Arb<NewDownload> =
    arbitrary {
        NewDownload(
            kind = Arb.element(DownloadKind.entries).bind(),
            mirrors = Arb.list(Arb.string(1..30, Codepoint.az()).map { "https://example.com/$it" }, 0..3).bind(),
            collection = Arb.element(CollectionKind.entries).orNull().bind(),
            categoryId =
                Arb
                    .long(1L..Long.MAX_VALUE)
                    .map(::CategoryId)
                    .orNull()
                    .bind(),
            profileId =
                Arb
                    .long(1L..Long.MAX_VALUE)
                    .map(::ProfileId)
                    .orNull()
                    .bind(),
            saveFolder = Arb.string(0..40, Codepoint.printableAscii()).orNull().bind(),
            fileName = Arb.string(0..40, Codepoint.printableAscii()).orNull().bind(),
            size = Arb.long(0L..Long.MAX_VALUE).orNull().bind(),
            priority = Arb.int().bind(),
            speedLimit = Arb.long(1L..Long.MAX_VALUE).orNull().bind(),
        )
    }

// Years 1 to 9999, whole milliseconds.
internal val arbInstant: Arb<Instant> =
    Arb.long(-62_135_596_800_000L..253_402_300_799_999L).map(Instant::fromEpochMilliseconds)
