package dev.anydm.engine.store

import app.cash.sqldelight.db.SqlDriver

class InMemoryCookieSealingTest : CookieSealingContract() {
    override fun newDriver(): SqlDriver = openInMemoryDatabase()
}
