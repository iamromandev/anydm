package dev.anydm.engine

import app.cash.sqldelight.db.SqlDriver
import dev.anydm.engine.store.openInMemoryDatabase

class InMemoryEngineCollectionTest : EngineCollectionContract() {
    override fun newDriver(): SqlDriver = openInMemoryDatabase()
}
