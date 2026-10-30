package dev.anydm.engine.store

import app.cash.sqldelight.db.SqlDriver

class InMemoryToolStoreTest : ToolStoreContract() {
    override fun newDriver(): SqlDriver = openInMemoryDatabase()
}
