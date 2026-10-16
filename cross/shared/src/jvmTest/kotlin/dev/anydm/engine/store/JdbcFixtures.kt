package dev.anydm.engine.store

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.io.files.Path
import java.util.Properties

internal fun openInMemoryDatabase(): SqlDriver = openJdbcDatabase(JdbcSqliteDriver.IN_MEMORY)

/** The file with no schema handling, for setting up and inspecting it behind the store's back. */
internal fun rawDriver(file: Path): SqlDriver = JdbcSqliteDriver("jdbc:sqlite:$file", Properties())
