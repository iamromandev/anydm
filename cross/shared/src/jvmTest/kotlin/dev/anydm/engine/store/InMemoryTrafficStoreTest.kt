package dev.anydm.engine.store

import app.cash.sqldelight.db.SqlDriver

class InMemoryTrafficStoreTest : TrafficStoreContract() {
    override fun newDriver(): SqlDriver = openInMemoryDatabase()
}
