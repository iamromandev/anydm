package dev.anydm.engine.net

import app.cash.sqldelight.db.SqlDriver
import dev.anydm.engine.store.openInMemoryDatabase

class InMemoryRuleKeeperTest : RuleKeeperContract() {
    override fun newDriver(): SqlDriver = openInMemoryDatabase()
}
