package dev.anydm.engine.store

import app.cash.sqldelight.db.SqlDriver

class InMemoryRuleStoreTest : RuleStoreContract() {
    override fun newDriver(): SqlDriver = openInMemoryDatabase()
}
