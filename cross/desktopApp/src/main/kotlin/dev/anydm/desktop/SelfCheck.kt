package dev.anydm.desktop

import com.sun.net.httpserver.HttpServer
import dev.anydm.engine.EngineState
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.platform.AppDirs
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.store.NewDownload
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.io.files.Path as KPath

/**
 * `anydm --self-check [folder]` (#611): starts the engine, adds a download from a one-file server on this machine and checks
 * the file that lands in [folder]. The packaging smoke test runs it against the installed app image, so a runtime that is
 * missing a module, a native library or a data folder fails here and not on a user's first launch. [run] answers null when
 * everything works, and otherwise says what didn't.
 */
internal class SelfCheck(
    private val source: EngineSource,
    private val folder: Path,
    private val timeoutMillis: Long = 60_000,
) {
    suspend fun run(): String? {
        (source.state.value as? EngineState.Failed)?.let { return "the engine failed: ${it.error.message}" }
        val missing = REQUIRED.filter { it !in source.available }
        if (missing.isNotEmpty()) {
            return "this build can't handle ${missing.joinToString {
                it.name.lowercase()
            }} downloads (torrent natives?)"
        }
        Files.createDirectories(folder)
        // A file from an earlier run would make the engine rename the new one and the check read the old one.
        Files.deleteIfExists(folder.resolve(NAME))
        val server = serve()
        try {
            val url = "http://127.0.0.1:${server.address.port}/$NAME"
            val added = source.add(NewDownload(DownloadKind.DIRECT, listOf(url), saveFolder = folder.toString(), fileName = NAME))
            val download = (added as? CommandResult.Done)?.download ?: return "the download was refused: $added"
            val status =
                withTimeoutOrNull(timeoutMillis) {
                    source.downloads
                        .first { list -> list.any { it.download.id == download.id && it.download.status in ENDED } }
                        .first { it.download.id == download.id }
                        .download.status
                } ?: return "the download did not finish in ${timeoutMillis / 1000} s"
            if (status != DownloadStatus.COMPLETED) return "the download ended as $status"
            val path = folder.resolve(NAME)
            if (!Files.exists(path)) return "the finished download's file is missing from $folder"
            val saved = Files.readAllBytes(path)
            return if (saved.contentEquals(PAYLOAD)) null else "the downloaded file differs from the one served"
        } finally {
            server.stop(0)
        }
    }

    private fun serve(): HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange ->
                exchange.responseHeaders.add("Content-Type", "application/octet-stream")
                exchange.sendResponseHeaders(200, PAYLOAD.size.toLong())
                exchange.responseBody.use { it.write(PAYLOAD) }
            }
            start()
        }

    companion object {
        /** The data, logs and cache of a self-check, all under [root]: it never touches the user's own folders, whatever the environment says. */
        fun dirs(root: Path) =
            AppDirs(KPath(root.resolve("data").toString()), KPath(root.resolve("logs").toString()), KPath(root.resolve("cache").toString()))

        const val FLAG = "--self-check"
        const val NAME = "self-check.bin"

        // Direct downloads and torrents: the two that need the runtime's modules and native libraries.
        private val REQUIRED = listOf(DownloadKind.DIRECT, DownloadKind.TORRENT)
        private val ENDED = setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.CANCELLED)
        val PAYLOAD = ByteArray(64 * 1024) { (it * 31).toByte() }
    }
}
