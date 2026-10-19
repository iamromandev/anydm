package dev.anydm.engine.store

import app.cash.sqldelight.db.SqlDriver

class InMemoryCollectionItemStoreTest : CollectionItemStoreContract() {
    override fun newDriver(): SqlDriver = openInMemoryDatabase()
}
