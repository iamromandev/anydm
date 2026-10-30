package dev.anydm.engine

import app.cash.sqldelight.db.SqlDriver
import dev.anydm.engine.store.openInMemoryDatabase

class InMemoryEngineRulesTimeTest : EngineRulesTimeContract() {
    override fun newDriver(): SqlDriver = openInMemoryDatabase()
}
