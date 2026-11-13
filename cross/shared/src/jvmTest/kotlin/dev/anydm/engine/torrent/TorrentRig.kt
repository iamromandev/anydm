package dev.anydm.engine.torrent

import dev.anydm.engine.http.Clock
import dev.anydm.engine.http.DiskIo
import dev.anydm.engine.http.EngineEvent
import dev.anydm.engine.http.FakeClock
import dev.anydm.engine.http.RateShares
import dev.anydm.engine.http.RealDiskIo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal const val KB = 1024
internal const val MB = 1024 * 1024

/** A generated torrent with its `.torrent` file saved under [dir], and the jobs that download it. */
internal class TestTorrent(
    val dir: Path,
    val files: List<Pair<String, Int>>,
    val pieceLength: Int,
    val single: Boolean = false,
    val name: String = "t",
    seed: Int = 1,
    announce: String? = "http://tracker.invalid/announce",
    announceList: List<List<String>>? = null,
) {
    val data = contentOf(files.sumOf { it.second }, seed)
    val built = torrentOf(files, pieceLength, data, name = name, single = single, announce = announce, announceList = announceList)
    val meta = assertIs<TorrentResult.Ok>(readTorrent(built.bytes)).meta
    val torrentFile: Path = dir.resolve("$name-$seed.torrent").also { Files.write(it, built.bytes) }
    val served = Served(built, meta)

    /** [data] cut at the files' borders. */
    val contents: List<ByteArray> by lazy {
        files
            .runningFold(0) { at, f -> at + f.second }
            .zipWithNext { a, b -> data.copyOfRange(a, b) }
    }

    fun job(
        id: String = "1",
        peers: List<PeerAddr> = emptyList(),
        folder: Path,
        pieces: PieceSet? = null,
        name: String = meta.name,
        peerId: ByteArray = Rig.PEER_ID,
        doneBytes: Long = 0,
    ) = TorrentJob(id, torrentFile.toString(), folder.toString(), name, pieces, peerId, peers, doneBytes)

    /** Every file of the torrent is under [folder]/[root] with exactly the right bytes (SHA-256), and no `.part` is left. */
    fun assertDownloaded(
        folder: Path,
        root: String = meta.name,
    ) {
        for ((i, f) in files.withIndex()) {
            val path = if (single) folder.resolve(root) else f.first.split('/').fold(folder.resolve(root)) { p, c -> p.resolve(c) }
            assertTrue(Files.isRegularFile(path), "$path is there")
            assertEquals(f.second.toLong(), Files.size(path), "size of $path")
            assertContentEquals(sha256(contents[i]), sha256(Files.readAllBytes(path)), "content of $path")
        }
        assertTrue(Files.walk(folder).use { s -> s.noneMatch { it.toString().endsWith(".part") } }, "no .part is left")
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
}

/** The engine under test with its events, a scratch folder and a way to wait; closed after the test. */
internal class Rig : AutoCloseable {
    val dir: Path = Files.createTempDirectory("torrents")
    val save: Path = Files.createDirectories(dir.resolve("save"))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val clock = FakeClock()
    val events = CopyOnWriteArrayList<EngineEvent>()
    private val toClose = CopyOnWriteArrayList<AutoCloseable>()

    fun torrent(
        files: List<Pair<String, Int>>,
        pieceLength: Int = 16 * KB,
        single: Boolean = false,
        name: String = "t",
        seed: Int = 1,
        announce: String? = "http://tracker.invalid/announce",
        announceList: List<List<String>>? = null,
    ) = TestTorrent(dir, files, pieceLength, single, name, seed, announce, announceList)

    fun swarm(
        torrents: List<TestTorrent>,
        vararg scripts: PeerScript,
    ): FakeSwarm = FakeSwarm(torrents.map { it.served }, scripts.toList()).also { toClose.add(it) }

    fun swarm(
        torrent: TestTorrent,
        vararg scripts: PeerScript,
    ): FakeSwarm = swarm(listOf(torrent), *scripts)

    fun engine(
        transport: PeerTransport = TcpPeerTransport(),
        trackers: TrackerClient = NoTrackers,
        disk: DiskIo = RealDiskIo(),
        clock: Clock = this.clock,
        shares: RateShares = RateShares(),
        limits: TorrentLimits = TorrentLimits(reportMs = 50),
        random: Random = Random(7),
    ): DefaultTorrentEngine {
        val e = DefaultTorrentEngine(transport, trackers, disk, clock, scope, shares, random, limits)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { e.events.collect { events.add(it) } }
        return e
    }

    fun eventsOf(id: String = "1") = events.filter { it.id == id }

    fun await(
        what: String,
        timeoutMs: Long = 30_000,
        id: String = "1",
        cond: () -> Boolean,
    ) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out waiting for $what; events=${eventsOf(id).takeLast(4)}" }
            Thread.sleep(5)
        }
    }

    fun awaitTerminal(
        id: String = "1",
        timeoutMs: Long = 30_000,
    ): EngineEvent {
        await("Finished/Failed", timeoutMs, id) { eventsOf(id).any { it is EngineEvent.Finished || it is EngineEvent.Failed } }
        return eventsOf(id).last { it is EngineEvent.Finished || it is EngineEvent.Failed }
    }

    fun awaitStopped(id: String = "1"): EngineEvent.Stopped {
        await("Stopped", id = id) { eventsOf(id).any { it is EngineEvent.Stopped } }
        return eventsOf(id).filterIsInstance<EngineEvent.Stopped>().last()
    }

    override fun close() {
        scope.cancel()
        toClose.forEach { runCatching { it.close() } }
        dir.toFile().deleteRecursively()
    }

    companion object {
        val PEER_ID: ByteArray = PEER_ID_PREFIX.encodeToByteArray() + ByteArray(12) { (it + 1).toByte() }
    }
}

/** No tracker can be reached: what a test that has its peers from the job's own list sees. */
internal object NoTrackers : TrackerClient {
    override suspend fun announce(
        url: String,
        req: AnnounceRequest,
    ): TrackerReply = throw java.io.IOException("no trackers in this test")
}

/** Counts connections the client holds open (up on a successful connect, down on the first close) and the most there were at once. */
internal class CountingTransport(
    private val inner: PeerTransport = TcpPeerTransport(),
) : PeerTransport {
    private val now = AtomicInteger()
    private val top = AtomicInteger()
    private val total = AtomicInteger()
    private val attempts = AtomicInteger()
    val open: Int get() = now.get()
    val peak: Int get() = top.get()

    /** Connections that were opened. */
    val opened: Int get() = total.get()

    /** Connects that were tried, the failed ones included. */
    val tried: Int get() = attempts.get()
    val addrs = CopyOnWriteArrayList<PeerAddr>()

    override suspend fun connect(
        addr: PeerAddr,
        timeoutMs: Int,
    ): PeerConn {
        attempts.incrementAndGet()
        addrs.add(addr)
        val c = inner.connect(addr, timeoutMs)
        top.accumulateAndGet(now.incrementAndGet()) { a, b -> maxOf(a, b) }
        total.incrementAndGet()
        val closed =
            java.util.concurrent.atomic
                .AtomicBoolean()
        return object : PeerConn by c {
            override fun close() {
                if (closed.compareAndSet(false, true)) now.decrementAndGet()
                c.close()
            }
        }
    }
}
