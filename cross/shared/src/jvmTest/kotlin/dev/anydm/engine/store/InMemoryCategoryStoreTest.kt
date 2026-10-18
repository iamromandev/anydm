package dev.anydm.engine.store

import app.cash.sqldelight.db.SqlDriver

class InMemoryCategoryStoreTest : CategoryStoreContract() {
    override fun newDriver(): SqlDriver = openInMemoryDatabase()
}
