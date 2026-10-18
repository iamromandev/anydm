package dev.anydm.engine.queue

import app.cash.sqldelight.db.SqlDriver
import dev.anydm.engine.store.openInMemoryDatabase

class InMemoryQueueDiskTest : QueueDiskContract() {
    override fun newDriver(): SqlDriver = openInMemoryDatabase()
}
