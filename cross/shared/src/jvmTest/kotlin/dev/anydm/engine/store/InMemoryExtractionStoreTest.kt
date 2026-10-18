package dev.anydm.engine.store

import app.cash.sqldelight.db.SqlDriver

class InMemoryExtractionStoreTest : ExtractionStoreContract() {
    override fun newDriver(): SqlDriver = openInMemoryDatabase()
}
