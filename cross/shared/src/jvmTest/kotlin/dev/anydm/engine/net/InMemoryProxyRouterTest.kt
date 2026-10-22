package dev.anydm.engine.net

import app.cash.sqldelight.db.SqlDriver
import dev.anydm.engine.store.openInMemoryDatabase

class InMemoryProxyRouterTest : ProxyRouterContract() {
    override fun newDriver(): SqlDriver = openInMemoryDatabase()
}
