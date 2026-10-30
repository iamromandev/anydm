package dev.anydm.engine.os

import app.cash.sqldelight.db.SqlDriver
import dev.anydm.engine.store.openInMemoryDatabase

class InMemoryBridgeServiceTest : BridgeServiceTest() {
    override fun newDriver(): SqlDriver = openInMemoryDatabase()
}
