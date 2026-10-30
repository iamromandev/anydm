package dev.anydm.desktop

import dev.anydm.engine.DownloadSnapshot
import dev.anydm.engine.Engine
import dev.anydm.engine.EngineEvent
import dev.anydm.engine.EngineState
import dev.anydm.engine.SpeedWindow
import dev.anydm.engine.files.Category
import dev.anydm.engine.store.Store
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** What the app reads from the engine and the two commands it gives it; the tests stand in for it with a fake. */
interface EngineSource {
    val state: StateFlow<EngineState>
    val downloads: StateFlow<List<DownloadSnapshot>>
    val totalSpeed: StateFlow<SpeedWindow>
    val events: Flow<EngineEvent>

    suspend fun categories(): List<Category>

    suspend fun start()

    suspend fun stop()
}

fun Engine.asSource(store: Store): EngineSource =
    object : EngineSource {
        override val state = this@asSource.state
        override val downloads = this@asSource.downloads
        override val totalSpeed = this@asSource.totalSpeed
        override val events = this@asSource.events

        override suspend fun categories() = store.categories.list()

        override suspend fun start() = this@asSource.start()

        // A failed engine's stop() does nothing, so its store is closed here.
        override suspend fun stop() {
            this@asSource.stop()
            if (this@asSource.state.value is EngineState.Failed) store.close()
        }
    }
