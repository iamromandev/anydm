package dev.anydm.engine

import app.cash.sqldelight.db.SqlDriver
import dev.anydm.engine.store.openInMemoryDatabase

class InMemoryEngineProxyTest : EngineProxyContract() {
    override fun newDriver(): SqlDriver = openInMemoryDatabase()
}
