package dev.anydm.engine.queue

import app.cash.sqldelight.db.SqlDriver
import dev.anydm.engine.store.openInMemoryDatabase

class InMemoryQueueToolTest : QueueToolContract() {
    override fun newDriver(): SqlDriver = openInMemoryDatabase()
}
