package dev.anydm.engine.store

import app.cash.sqldelight.db.SqlDriver

class InMemoryProxyStoreTest : ProxyStoreContract() {
    override fun newDriver(): SqlDriver = openInMemoryDatabase()
}
