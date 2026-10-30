package dev.anydm.engine.store

import app.cash.sqldelight.db.SqlDriver

class InMemoryProfileStoreTest : ProfileStoreContract() {
    override fun newDriver(): SqlDriver = openInMemoryDatabase()
}
