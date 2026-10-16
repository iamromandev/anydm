package dev.anydm.engine.store

import app.cash.sqldelight.db.SqlDriver
import dev.anydm.engine.platform.deleteRecursively
import dev.anydm.engine.platform.newTempFolder
import kotlinx.io.files.Path

// A file database opens a connection per statement outside a transaction: the same contracts as in memory, through
// the production connection setup. This is what shows foreign keys and last_insert_rowid working.
class FileDownloadStoreTest : DownloadStoreContract() {
    private val root = newTempFolder("anydm-store-test-")

    override fun newDriver(): SqlDriver = openDatabaseFile(Path(root, DATABASE_FILE))

    override fun cleanUp() = deleteRecursively(root)
}

class FileSettingsStoreTest : SettingsStoreContract() {
    private val root = newTempFolder("anydm-store-test-")

    override fun newDriver(): SqlDriver = openDatabaseFile(Path(root, DATABASE_FILE))

    override fun cleanUp() = deleteRecursively(root)
}
