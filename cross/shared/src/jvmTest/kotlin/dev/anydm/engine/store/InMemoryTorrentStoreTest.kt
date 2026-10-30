package dev.anydm.engine.store

import app.cash.sqldelight.db.SqlDriver

class InMemoryTorrentStoreTest : TorrentStoreContract() {
    override fun newDriver(): SqlDriver = openInMemoryDatabase()
}
