package dev.anydm.engine

import app.cash.sqldelight.db.SqlDriver
import dev.anydm.engine.store.openInMemoryDatabase

class InMemoryEngineAfterQueueTest : EngineAfterQueueContract() {
    override fun newDriver(): SqlDriver = openInMemoryDatabase()
}
