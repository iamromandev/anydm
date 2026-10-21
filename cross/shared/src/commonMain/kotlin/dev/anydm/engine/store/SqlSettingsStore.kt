package dev.anydm.engine.store

import dev.anydm.engine.core.CookieSource
import dev.anydm.engine.core.Pacing
import dev.anydm.engine.core.PacingPreset
import dev.anydm.engine.core.PacingValues
import dev.anydm.engine.core.ProfileId
import dev.anydm.engine.files.ClashPolicy
import dev.anydm.engine.log.Log
import dev.anydm.engine.net.AfterQueueAction
import dev.anydm.engine.store.db.AnydmDb
import dev.anydm.engine.tools.YtDlpChannel
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
            torrentPort = read(TORRENT_PORT, DEFAULTS.torrentPort) { it.toIntOrNull()?.takeIf { n -> n in 0..65535 } },
            playlistFile = read(PLAYLIST_FILE, DEFAULTS.playlistFile) { it.toBooleanStrictOrNull() },
            defaultProfileId =
                read(DEFAULT_PROFILE, DEFAULTS.defaultProfileId) { data -> data.toLongOrNull()?.takeIf { it > 0 }?.let(::ProfileId) },
            maxRetries = read(MAX_RETRIES, DEFAULTS.maxRetries) { it.toIntOrNull()?.takeIf { n -> n >= 0 } },
            debugLogging = read(DEBUG_LOGGING, DEFAULTS.debugLogging) { it.toBooleanStrictOrNull() },
            connectionsPerDownload = read(CONNECTIONS, DEFAULTS.connectionsPerDownload) { it.toIntOrNull()?.takeIf { n -> n in 1..16 } },
            globalSpeedLimit = read(GLOBAL_SPEED_LIMIT, DEFAULTS.globalSpeedLimit) { it.toLongOrNull()?.takeIf { n -> n > 0 } },
            seedRatio = read(SEED_RATIO, DEFAULTS.seedRatio) { it.toDoubleOrNull()?.takeIf { n -> n > 0 && n.isFinite() } },
            seedMinutes = read(SEED_MINUTES, DEFAULTS.seedMinutes) { it.toIntOrNull()?.takeIf { n -> n > 0 } },
            globalUploadLimit = read(GLOBAL_UPLOAD_LIMIT, DEFAULTS.globalUploadLimit) { it.toLongOrNull()?.takeIf { n -> n > 0 } },
            ytDlpChannel = read(YT_DLP_CHANNEL, DEFAULTS.ytDlpChannel) { name -> YtDlpChannel.entries.firstOrNull { it.name == name } },
            systemTools = read(SYSTEM_TOOLS, DEFAULTS.systemTools) { it.toBooleanStrictOrNull() },
            historyLimit = read(HISTORY_LIMIT, DEFAULTS.historyLimit) { it.toIntOrNull()?.takeIf { n -> n >= 1 } },
            freeSpaceReserve = read(FREE_SPACE_RESERVE, DEFAULTS.freeSpaceReserve) { it.toLongOrNull()?.takeIf { n -> n >= 0 } },
            clashPolicy = read(CLASH_POLICY, DEFAULTS.clashPolicy) { name -> ClashPolicy.entries.firstOrNull { it.name == name } },
            cookieSource = read(COOKIE_SOURCE, DEFAULTS.cookieSource) { CookieSource.decode(it) },
            pacing = readPacing(::read),
            slowMode = read(SLOW_MODE, DEFAULTS.slowMode) { it.toBooleanStrictOrNull() },
            slowModeLimit = read(SLOW_MODE_LIMIT, DEFAULTS.slowModeLimit) { it.toLongOrNull()?.takeIf { n -> n > 0 } },
            keepAwake = read(KEEP_AWAKE, DEFAULTS.keepAwake) { it.toBooleanStrictOrNull() },
            afterQueue = read(AFTER_QUEUE, DEFAULTS.afterQueue) { name -> AfterQueueAction.entries.firstOrNull { it.name == name } },
        )
    }

    // A pacing preset, and for CUSTOM its three pauses; an unreadable one is the default.
    private fun readPacing(read: (String, Pacing, (String) -> Pacing?) -> Pacing): Pacing =
        read(PACING, DEFAULTS.pacing) { name ->
            val preset = PacingPreset.entries.firstOrNull { it.name == name } ?: return@read null
            if (preset != PacingPreset.CUSTOM) return@read Pacing(preset)
            val values =
                listOf(PACING_REQUEST, PACING_MIN, PACING_MAX).map { key ->
                    queries
                        .selectAll()
                        .executeAsList()
                        .firstOrNull {
                            it.name ==
                                key
                        }?.data_
                        ?.toDoubleOrNull()
                }
            if (values.any { it == null }) return@read null
            try {
                Pacing(preset, PacingValues(values[0]!!, values[1]!!, values[2]!!))
            } catch (_: IllegalArgumentException) {
                null
            }
        }

    // Only values that differ from their default take a row; names this version doesn't know are never touched.
    private fun write(settings: Settings) {
        put(CONCURRENCY_LIMIT, settings.concurrencyLimit.takeIf { it != DEFAULTS.concurrencyLimit }?.toString())
        put(DEFAULT_SAVE_FOLDER, settings.defaultSaveFolder.takeIf { it != DEFAULTS.defaultSaveFolder }?.toString())
        put(GLOBAL_PAUSE, settings.globalPause.takeIf { it != DEFAULTS.globalPause }?.toString())
        put(TORRENT_PORT, settings.torrentPort.takeIf { it != DEFAULTS.torrentPort }?.toString())
        put(PLAYLIST_FILE, settings.playlistFile.takeIf { it != DEFAULTS.playlistFile }?.toString())
        put(DEFAULT_PROFILE, settings.defaultProfileId?.value?.toString())
        put(MAX_RETRIES, settings.maxRetries.takeIf { it != DEFAULTS.maxRetries }?.toString())
        put(DEBUG_LOGGING, settings.debugLogging.takeIf { it != DEFAULTS.debugLogging }?.toString())
        put(CONNECTIONS, settings.connectionsPerDownload.takeIf { it != DEFAULTS.connectionsPerDownload }?.toString())
        put(GLOBAL_SPEED_LIMIT, settings.globalSpeedLimit?.toString())
        put(SEED_RATIO, settings.seedRatio?.toString())
        put(SEED_MINUTES, settings.seedMinutes?.toString())
        put(GLOBAL_UPLOAD_LIMIT, settings.globalUploadLimit?.toString())
        put(YT_DLP_CHANNEL, settings.ytDlpChannel.takeIf { it != DEFAULTS.ytDlpChannel }?.name)
        put(SYSTEM_TOOLS, settings.systemTools.takeIf { it != DEFAULTS.systemTools }?.toString())
        put(HISTORY_LIMIT, settings.historyLimit.takeIf { it != DEFAULTS.historyLimit }?.toString())
        put(FREE_SPACE_RESERVE, settings.freeSpaceReserve.takeIf { it != DEFAULTS.freeSpaceReserve }?.toString())
        put(CLASH_POLICY, settings.clashPolicy.takeIf { it != DEFAULTS.clashPolicy }?.name)
        put(COOKIE_SOURCE, settings.cookieSource.takeIf { it != DEFAULTS.cookieSource }?.let(CookieSource::encode))
        put(
            PACING,
            settings.pacing.preset
                .takeIf { it != PacingPreset.OFF }
                ?.name,
        )
        val custom = settings.pacing.takeIf { it.preset == PacingPreset.CUSTOM }?.custom
        put(PACING_REQUEST, custom?.requestSleep?.toString())
        put(PACING_MIN, custom?.intervalMin?.toString())
        put(PACING_MAX, custom?.intervalMax?.toString())
        put(SLOW_MODE, settings.slowMode.takeIf { it != DEFAULTS.slowMode }?.toString())
        put(SLOW_MODE_LIMIT, settings.slowModeLimit.takeIf { it != DEFAULTS.slowModeLimit }?.toString())
        put(KEEP_AWAKE, settings.keepAwake.takeIf { it != DEFAULTS.keepAwake }?.toString())
        put(AFTER_QUEUE, settings.afterQueue.takeIf { it != DEFAULTS.afterQueue }?.name)
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
        const val PLAYLIST_FILE = "collection.playlist_file"
        const val TORRENT_PORT = "torrent.port"
        const val DEFAULT_SAVE_FOLDER = "files.default_save_folder"
        const val MAX_RETRIES = "retry.max_retries"
        const val DEBUG_LOGGING = "log.debug"
        const val CONNECTIONS = "http.connections"
        const val GLOBAL_SPEED_LIMIT = "net.global_speed_limit"
        const val GLOBAL_UPLOAD_LIMIT = "net.global_upload_limit"
        const val SLOW_MODE = "net.slow_mode"
        const val SLOW_MODE_LIMIT = "net.slow_mode_limit"
        const val KEEP_AWAKE = "net.keep_awake"
        const val AFTER_QUEUE = "net.after_queue"
        const val SEED_RATIO = "torrent.seed_ratio"
        const val SEED_MINUTES = "torrent.seed_minutes"
        const val CLASH_POLICY = "files.clash_policy"
        const val FREE_SPACE_RESERVE = "files.free_space_reserve"
        const val HISTORY_LIMIT = "files.history_limit"
        const val YT_DLP_CHANNEL = "tools.yt_dlp_channel"
        const val SYSTEM_TOOLS = "tools.system_tools"
        const val COOKIE_SOURCE = "media.cookie_source"
        const val PACING = "media.pacing"
        const val PACING_REQUEST = "media.pacing_request_sleep"
        const val PACING_MIN = "media.pacing_interval_min"
        const val PACING_MAX = "media.pacing_interval_max"
    }
}

/** The setting that names the default profile; [SqlProfileStore] clears it when that profile goes. */
internal const val DEFAULT_PROFILE = "profile.default"
