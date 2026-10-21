package dev.anydm.engine.queue

import app.cash.sqldelight.db.SqlDriver
import dev.anydm.engine.store.openInMemoryDatabase

class InMemoryQueueRulesTest : QueueRulesContract() {
    override fun newDriver(): SqlDriver = openInMemoryDatabase()
}
