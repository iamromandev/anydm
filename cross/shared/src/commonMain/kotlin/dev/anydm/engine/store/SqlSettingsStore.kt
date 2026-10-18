package dev.anydm.engine.store

import dev.anydm.engine.log.Log
import dev.anydm.engine.store.db.AnydmDb
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path

internal class SqlSettingsStore(
    private val database: AnydmDb,
    private val io: CoroutineDispatcher,
    private val log: Log = Log.None,
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

        // A stored value that doesn't parse is reported and replaced by the default.
        fun <T> read(
            name: String,
            default: T,
            parse: (String) -> T?,
        ): T {
            val data = stored[name] ?: return default
            return parse(data) ?: default.also { log.warn("store") { "setting $name unreadable, using the default" } }
        }
        return Settings(
            concurrencyLimit = read(CONCURRENCY_LIMIT, DEFAULTS.concurrencyLimit) { it.toIntOrNull()?.takeIf { n -> n >= 1 } },
            defaultSaveFolder =
                read(DEFAULT_SAVE_FOLDER, DEFAULTS.defaultSaveFolder) { data ->
                    data.takeIf { it.isNotEmpty() }?.let(::Path)?.takeIf { it.isAbsolute }
                },
            globalPause = read(GLOBAL_PAUSE, DEFAULTS.globalPause) { it.toBooleanStrictOrNull() },
            maxRetries = read(MAX_RETRIES, DEFAULTS.maxRetries) { it.toIntOrNull()?.takeIf { n -> n >= 0 } },
            debugLogging = read(DEBUG_LOGGING, DEFAULTS.debugLogging) { it.toBooleanStrictOrNull() },
            connectionsPerDownload = read(CONNECTIONS, DEFAULTS.connectionsPerDownload) { it.toIntOrNull()?.takeIf { n -> n in 1..16 } },
            globalSpeedLimit = read(GLOBAL_SPEED_LIMIT, DEFAULTS.globalSpeedLimit) { it.toLongOrNull()?.takeIf { n -> n > 0 } },
        )
    }

    // Only values that differ from their default take a row; names this version doesn't know are never touched.
    private fun write(settings: Settings) {
        put(CONCURRENCY_LIMIT, settings.concurrencyLimit.takeIf { it != DEFAULTS.concurrencyLimit }?.toString())
        put(DEFAULT_SAVE_FOLDER, settings.defaultSaveFolder.takeIf { it != DEFAULTS.defaultSaveFolder }?.toString())
        put(GLOBAL_PAUSE, settings.globalPause.takeIf { it != DEFAULTS.globalPause }?.toString())
        put(MAX_RETRIES, settings.maxRetries.takeIf { it != DEFAULTS.maxRetries }?.toString())
        put(DEBUG_LOGGING, settings.debugLogging.takeIf { it != DEFAULTS.debugLogging }?.toString())
        put(CONNECTIONS, settings.connectionsPerDownload.takeIf { it != DEFAULTS.connectionsPerDownload }?.toString())
        put(GLOBAL_SPEED_LIMIT, settings.globalSpeedLimit?.toString())
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
        const val MAX_RETRIES = "retry.max_retries"
        const val DEBUG_LOGGING = "log.debug"
        const val CONNECTIONS = "http.connections"
        const val GLOBAL_SPEED_LIMIT = "net.global_speed_limit"
    }
}
