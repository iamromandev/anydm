package dev.anydm.engine.store

import app.cash.sqldelight.db.SqlDriver

// In memory the JDBC driver keeps one connection for good, which hides per-connection problems: FileStoreTest
// runs the same contracts on a file.
class InMemoryDownloadStoreTest : DownloadStoreContract() {
    override fun newDriver(): SqlDriver = openInMemoryDatabase()
}

class InMemorySettingsStoreTest : SettingsStoreContract() {
    override fun newDriver(): SqlDriver = openInMemoryDatabase()
}
