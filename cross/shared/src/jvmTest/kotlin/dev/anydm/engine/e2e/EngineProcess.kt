package dev.anydm.engine.e2e

import dev.anydm.engine.Engine
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.http.HttpProtocol
import dev.anydm.engine.http.OkHttpTransport
import dev.anydm.engine.net.DefaultRouteNetwork
import dev.anydm.engine.platform.DesktopFileSystem
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.openDatabaseFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds

/**
 * The engine in a child JVM, for the crash tests to kill (#576). Args: database file, save folder, URL. On a first run
 * (an empty store) it adds the download; on a restart the stored job resumes. Prints `progress <bytes>` as it goes, then
 * `done` (exit 0) or `failed <message>` (exit 1).
 */
object EngineProcess {
    @JvmStatic
    fun main(args: Array<String>) {
        val (database, folder, url) = args
        val code =
            runBlocking {
                val store = Store(openDatabaseFile(Path(database)), Dispatchers.IO)
                val engine =
                    Engine(
                        store = store,
                        fileSystem = DesktopFileSystem(),
                        protocols = mapOf(DownloadKind.DIRECT to HttpProtocol(OkHttpTransport(), connections = { 4 })),
                        defaultFolder = Path(folder),
                        checkpointEvery = 100.milliseconds,
                        identity = DefaultRouteNetwork(),
                    )
                if (store.downloads.list().isEmpty()) {
                    store.downloads.add(NewDownload(DownloadKind.DIRECT, mirrors = listOf(url), saveFolder = folder))
                }
                engine.start()
                val reporter =
                    launch {
                        var last = -1L
                        engine.downloads.collect { all ->
                            val now = all.firstOrNull()?.downloaded ?: 0L
                            if (now != last) {
                                last = now
                                println("progress $now")
                                System.out.flush()
                            }
                        }
                    }
                val end =
                    engine.downloads
                        .map { all -> all.firstOrNull() }
                        .first { it != null && it.download.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED) }!!
                reporter.cancel()
                engine.stop()
                if (end.download.status == DownloadStatus.COMPLETED) {
                    println("progress ${end.downloaded}")
                    println("done")
                    0
                } else {
                    println("failed ${end.lastFailure?.message ?: "unknown"}")
                    1
                }
            }
        System.out.flush()
        exitProcess(code)
    }
}
