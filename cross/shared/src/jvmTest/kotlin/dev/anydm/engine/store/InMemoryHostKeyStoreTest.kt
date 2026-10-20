package dev.anydm.engine.store

import app.cash.sqldelight.db.SqlDriver

class InMemoryHostKeyStoreTest : HostKeyStoreContract() {
    override fun newDriver(): SqlDriver = openInMemoryDatabase()
}
