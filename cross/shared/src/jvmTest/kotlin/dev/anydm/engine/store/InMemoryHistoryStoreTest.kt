package dev.anydm.engine.store

import app.cash.sqldelight.db.SqlDriver

class InMemoryHistoryStoreTest : HistoryStoreContract() {
    override fun newDriver(): SqlDriver = openInMemoryDatabase()
}
