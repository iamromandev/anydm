package dev.anydm.engine.tools

import app.cash.sqldelight.db.SqlDriver
import dev.anydm.engine.store.openInMemoryDatabase

class InMemoryEngineToolTest : EngineToolContract() {
    override fun newDriver(): SqlDriver = openInMemoryDatabase()
}
