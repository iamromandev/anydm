package dev.anydm.engine.e2e

import dev.anydm.engine.Engine
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.http.Fault
import dev.anydm.engine.http.FaultServer
import dev.anydm.engine.http.HttpProtocol
import dev.anydm.engine.http.OkHttpTransport
import dev.anydm.engine.platform.DesktopFileSystem
import dev.anydm.engine.platform.deleteRecursively
import dev.anydm.engine.platform.newTempFolder
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.openDatabaseFile
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * The story's "at full speed" (#538, #576): a 64 MiB file from a server that gives each connection 4 MiB/s, so 4
 * connections can reach 16 MiB/s. The ramp adds a connection a second, so a perfect run averages about 11.6 MiB/s.
 */
class ThroughputEndToEndTest {
    private val mib = 1L shl 20
    private val size = 64 shl 20
    private val perConnection = 4 * mib
    private val limit = 4 * perConnection.toDouble()
    private val root = newTempFolder("anydm-throughput-")
    private val server = FaultServer()
    private val source = Random(13).nextBytes(size)

    @AfterTest
    fun cleanUp() {
        server.close()
        deleteRecursively(root)
    }

    @Test
    fun aLargeFileRunsNearTheServersLimit() =
        runBlocking<Unit> {
            server.serve("/big.bin", source, etag = "\"t1\"")
            server.fault("/big.bin", Fault.Throttle(perConnection))
            val folder = Path(root, "save")
            File(folder.toString()).mkdirs()
            val store = Store(openDatabaseFile(Path(root, "anydm.db")), Dispatchers.IO)
            val engine =
                Engine(
                    store = store,
                    fileSystem = DesktopFileSystem(),
                    protocols = mapOf(DownloadKind.DIRECT to HttpProtocol(OkHttpTransport(), connections = { 4 })),
                    defaultFolder = folder,
                )
            engine.start()
            var peak = 0L
            val watch = launch { engine.totalSpeed.collect { if (it.current > peak) peak = it.current } }

            val took =
                try {
                    measureTime {
                        engine.add(
                            NewDownload(DownloadKind.DIRECT, mirrors = listOf(server.url("/big.bin")), saveFolder = folder.toString()),
                        )
                        engine.awaitFinished(60.seconds)
                    }
                } finally {
                    watch.cancel()
                    engine.stop()
                }

            val average = size / took.inWholeMilliseconds.coerceAtLeast(1) * 1000.0
            println(
                "throughput: ${size / mib} MiB in $took, average ${"%.1f".format(
                    average / mib,
                )} MiB/s, peak ${"%.1f".format(peak.toDouble() / mib)} MiB/s",
            )
            withClue("average ${"%.1f".format(average / mib)} MiB/s, peak ${"%.1f".format(peak.toDouble() / mib)} MiB/s") {
                average shouldBeGreaterThanOrEqual AVERAGE_FLOOR * limit
                peak.toDouble() shouldBeGreaterThanOrEqual PEAK_FLOOR * limit
                val digest = { data: ByteArray -> MessageDigest.getInstance("SHA-256").digest(data).toList() }
                digest(File(folder.toString(), "big.bin").readBytes()) shouldBe digest(source)
            }
        }

    private companion object {
        // The ramp (a connection a second) costs about three seconds of a 64 MiB run, so the average floor leaves room
        // for a loaded machine; the peak shows the run reaches the limit.
        const val AVERAGE_FLOOR = 0.5
        const val PEAK_FLOOR = 0.8
    }
}
