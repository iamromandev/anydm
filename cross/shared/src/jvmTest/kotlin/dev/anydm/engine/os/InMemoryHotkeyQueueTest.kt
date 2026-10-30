package dev.anydm.engine.os

import app.cash.sqldelight.db.SqlDriver
import dev.anydm.engine.store.openInMemoryDatabase

class InMemoryHotkeyQueueTest : HotkeyQueueTest() {
    override fun newDriver(): SqlDriver = openInMemoryDatabase()
}
