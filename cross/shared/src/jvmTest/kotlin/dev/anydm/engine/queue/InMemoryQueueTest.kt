package dev.anydm.engine.queue

import app.cash.sqldelight.db.SqlDriver
import dev.anydm.engine.store.openInMemoryDatabase

class InMemoryQueueTest : QueueContract() {
    override fun newDriver(): SqlDriver = openInMemoryDatabase()
}
