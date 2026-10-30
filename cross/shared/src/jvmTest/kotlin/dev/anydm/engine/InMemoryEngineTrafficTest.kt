package dev.anydm.engine

import app.cash.sqldelight.db.SqlDriver
import dev.anydm.engine.store.openInMemoryDatabase

class InMemoryEngineTrafficTest : EngineTrafficContract() {
    override fun newDriver(): SqlDriver = openInMemoryDatabase()
}
