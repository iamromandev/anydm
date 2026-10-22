package dev.anydm.engine.os

import app.cash.sqldelight.db.SqlDriver
import dev.anydm.engine.store.openInMemoryDatabase

class InMemoryStartupArgsTest : StartupArgsTest() {
    override fun newDriver(): SqlDriver = openInMemoryDatabase()
}
