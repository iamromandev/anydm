package dev.anydm.engine.torrent

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** An announce request as the fake UDP tracker read it. */
internal class UdpAnnounceSeen(
    val connectionId: Long,
    val infoHash: ByteArray,
    val downloaded: Long,
    val left: Long,
    val event: Int,
    val port: Int,
)

/**
 * A BEP 15 tracker on a free loopback UDP port. Connect and announce requests are answered like a real tracker would (a connection
 * id from a connect is the only one an announce is accepted with), and the switches make it misbehave. Everything it receives is recorded.
 */
internal class FakeUdpTracker(
    @Volatile var peers: List<PeerAddr> = emptyList(),
    @Volatile var interval: Int = 1_800,
) : AutoCloseable {
    private val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
    val url: String get() = "udp://127.0.0.1:${socket.localPort}/announce"

    /** Ignores this many connect requests (the first ones) without answering. */
    @Volatile var dropConnects = 0

    /** Ignores this many announce requests (the first ones) without answering. */
    @Volatile var dropAnnounces = 0

    /** Answers announces with an error message when set. */
    @Volatile var announceError: String? = null

    /** Answers connects with an error message when set. */
    @Volatile var connectError: String? = null

    /** Sends junk before every answer: a datagram with another transaction id and one that is too short. */
    @Volatile var stray = false

    /** Sends the answer to an announce with the wrong action first, then the right one. */
    @Volatile var wrongActionFirst = false

    /** Sends every answer from a socket with another port first (the client must ignore it). */
    @Volatile var strayFromOtherPort = false

    /** Answers an announce with a connection id other than the one issued as an error "expired". */
    @Volatile var rejectKnownIds = false

    private val connects = AtomicInteger()
    private val announces = AtomicInteger()
    val connectCount: Int get() = connects.get()
    val announceCount: Int get() = announces.get()
    val seen = CopyOnWriteArrayList<UdpAnnounceSeen>()
    private val ids = java.util.Collections.synchronizedSet(HashSet<Long>())
    private val dropped = AtomicInteger()
    private val droppedAnnounces = AtomicInteger()
    private var nextId = 0x1122334455667788L

    /** Makes every connection id issued so far worthless, as a tracker does a minute later. */
    fun expireIds() = ids.clear()

    private val worker =
        thread(isDaemon = true, name = "fake-udp-tracker") {
            val buf = ByteArray(2_000)
            while (!socket.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(p)
                } catch (_: SocketException) {
                    break
                }
                runCatching { handle(p.data.copyOf(p.length), p) }
            }
        }

    private fun handle(
        data: ByteArray,
        from: DatagramPacket,
    ) {
        if (data.size < 16) return
        val bb = ByteBuffer.wrap(data)
        val connectionId = bb.getLong(0)
        val action = bb.getInt(8)
        val tx = bb.getInt(12)
        when {
            action == 0 && connectionId == 0x41727101980L && data.size == 16 -> connect(tx, from)
            action == 1 && data.size >= 98 -> announce(connectionId, tx, bb, from)
        }
    }

    private fun connect(
        tx: Int,
        from: DatagramPacket,
    ) {
        connects.incrementAndGet()
        if (dropped.getAndIncrement() < dropConnects) return
        connectError?.let { return reply(from, error(tx, it)) }
        val id = synchronized(this) { nextId++ }
        ids.add(id)
        val out =
            ByteBuffer
                .allocate(16)
                .putInt(0)
                .putInt(tx)
                .putLong(id)
                .array()
        reply(from, out)
    }

    private fun announce(
        connectionId: Long,
        tx: Int,
        bb: ByteBuffer,
        from: DatagramPacket,
    ) {
        announces.incrementAndGet()
        val hash = ByteArray(20).also { bb.get(16, it) }
        seen.add(UdpAnnounceSeen(connectionId, hash, bb.getLong(56), bb.getLong(64), bb.getInt(80), bb.getShort(96).toInt() and 0xFFFF))
        if (droppedAnnounces.getAndIncrement() < dropAnnounces) return
        if (connectionId !in ids || rejectKnownIds) return reply(from, error(tx, "connection id expired"))
        announceError?.let { return reply(from, error(tx, it)) }
        val body = peers.flatMap { p -> p.host.split('.').map { it.toInt().toByte() } + listOf((p.port ushr 8).toByte(), p.port.toByte()) }
        val out =
            ByteBuffer
                .allocate(20 + body.size)
                .putInt(1)
                .putInt(tx)
                .putInt(interval)
                .putInt(0)
                .putInt(peers.size)
                .put(body.toByteArray())
                .array()
        if (wrongActionFirst) reply(from, out.copyOf().also { it[3] = 9 })
        reply(from, out)
    }

    private fun error(
        tx: Int,
        text: String,
    ) = ByteBuffer
        .allocate(8 + text.length)
        .putInt(3)
        .putInt(tx)
        .put(text.encodeToByteArray())
        .array()

    private fun reply(
        to: DatagramPacket,
        bytes: ByteArray,
    ) {
        if (stray) {
            socket.send(DatagramPacket(ByteArray(4), 4, to.socketAddress))
            // a well formed answer to a transaction nobody asked about
            val other = bytes.copyOf()
            other[4] = (other[4].toInt() xor 0x55).toByte()
            socket.send(DatagramPacket(other, other.size, to.socketAddress))
        }
        if (strayFromOtherPort) {
            DatagramSocket(0, InetAddress.getLoopbackAddress()).use { s ->
                // an error with the right transaction id, from a port the client did not talk to: believing it would fail the announce
                val evil =
                    ByteBuffer
                        .allocate(12)
                        .putInt(3)
                        .put(bytes, 4, 4)
                        .put("evil".encodeToByteArray())
                        .array()
                s.send(DatagramPacket(evil, evil.size, to.socketAddress))
            }
        }
        socket.send(DatagramPacket(bytes, bytes.size, to.socketAddress))
    }

    override fun close() {
        socket.close()
        worker.join(1_000)
    }
}
