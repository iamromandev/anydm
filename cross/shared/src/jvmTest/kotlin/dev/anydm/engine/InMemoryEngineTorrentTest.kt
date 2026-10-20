package dev.anydm.engine

import app.cash.sqldelight.db.SqlDriver
import dev.anydm.engine.store.openInMemoryDatabase

class InMemoryEngineTorrentTest : EngineTorrentContract() {
    override fun newDriver(): SqlDriver = openInMemoryDatabase()
}
