package dev.anydm.engine.store

import dev.anydm.engine.store.db.AnydmDb
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path

internal class SqlSettingsStore(
    private val database: AnydmDb,
    private val io: CoroutineDispatcher,
) : SettingsStore {
    private val queries = database.settingQueries

    override suspend fun load(): Settings = withContext(io) { database.transactionWithResult { read() } }

    // Like every write in the store, an update that has been asked for is made even if its caller is cancelled.
    private val writeContext = io + NonCancellable

    override suspend fun update(change: (Settings) -> Settings): Settings =
        withContext(writeContext) { database.transactionWithResult { change(read()).also(::write) } }

    // A value this version can't read gets its default: a bad setting must not stop the engine from starting.
    private fun read(): Settings {
        val stored = queries.selectAll().executeAsList().associate { it.name to it.data_ }
        return Settings(
            concurrencyLimit = stored[CONCURRENCY_LIMIT]?.toIntOrNull()?.takeIf { it >= 1 } ?: DEFAULTS.concurrencyLimit,
            defaultSaveFolder =
                stored[DEFAULT_SAVE_FOLDER]?.takeIf { it.isNotEmpty() }?.let(::Path)?.takeIf { it.isAbsolute }
                    ?: DEFAULTS.defaultSaveFolder,
            globalPause = stored[GLOBAL_PAUSE]?.toBooleanStrictOrNull() ?: DEFAULTS.globalPause,
        )
    }

    // Only values that differ from their default take a row; names this version doesn't know are never touched.
    private fun write(settings: Settings) {
        put(CONCURRENCY_LIMIT, settings.concurrencyLimit.takeIf { it != DEFAULTS.concurrencyLimit }?.toString())
        put(DEFAULT_SAVE_FOLDER, settings.defaultSaveFolder.takeIf { it != DEFAULTS.defaultSaveFolder }?.toString())
        put(GLOBAL_PAUSE, settings.globalPause.takeIf { it != DEFAULTS.globalPause }?.toString())
    }

    private fun put(
        name: String,
        data: String?,
    ) {
        if (data == null) queries.remove(name) else queries.put(name, data)
    }

    private companion object {
        val DEFAULTS = Settings()
        const val CONCURRENCY_LIMIT = "queue.concurrency_limit"
        const val GLOBAL_PAUSE = "queue.global_pause"
        const val DEFAULT_SAVE_FOLDER = "files.default_save_folder"
    }
}
