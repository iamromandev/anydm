package dev.anydm.engine.ftp

import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import kotlin.time.Duration

/** A read that goes wrong once: after [afterBytes] it throws [error], or when that is null just ends (a short read). */
internal class ReadFault(
    val afterBytes: Int,
    val error: RemoteException? = null,
)

/**
 * A remote server in memory that every client from [factory] talks to: [files] by path, and hooks to refuse a login or
 * a file, to limit connections, to refuse `REST` and to break reads. [connects] lists each connect (target and login),
 * [opened] and [closed] count the clients, [readLog] each read as (offset, length).
 */
internal class FakeRemoteServer {
    val files = mutableMapOf<String, ByteArray>()
    var modified: Long? = 1_700_000_000L
    var reportsSize = true

    /** Thrown by the next connects while set. */
    var connectFailure: RemoteException? = null

    /** Thrown by every stat while set. */
    var statFailure: RemoteException? = null

    /** Connections the server lets be open at once; one more is refused as too many. */
    var maxConnections: Int? = null

    /** A read from an offset above zero fails as REST unsupported. */
    var restUnsupported = false

    /** Time each chunk takes, so a test can stop a transfer part-way. */
    var chunkDelay: Duration = Duration.ZERO

    /** Applied to the reads in order, one each. */
    val faults = mutableListOf<ReadFault>()

    val connects = mutableListOf<Pair<RemoteTarget, RemoteLogin>>()
    val readLog = mutableListOf<Pair<Long, Long?>>()
    var opened = 0
        private set
    var closed = 0
        private set
    var active = 0
        private set
    var peakActive = 0
        private set
    var peakReads = 0
        private set
    private var reading = 0

    val factory = RemoteClientFactory { FakeClient() }

    inner class FakeClient : RemoteClient {
        private var connected = false

        init {
            opened++
        }

        override suspend fun connect(
            target: RemoteTarget,
            login: RemoteLogin,
        ) {
            connects += target to login
            connectFailure?.let { throw it }
            if (maxConnections?.let { active >= it } == true) {
                throw RemoteException(RemoteFailure.TOO_MANY_CONNECTIONS, "too many connections")
            }
            connected = true
            active++
            peakActive = maxOf(peakActive, active)
        }

        override suspend fun stat(path: String): RemoteStat {
            statFailure?.let { throw it }
            val bytes = files[path] ?: throw RemoteException(RemoteFailure.GONE, "no such file")
            return RemoteStat(if (reportsSize) bytes.size.toLong() else null, modified)
        }

        override suspend fun read(
            path: String,
            offset: Long,
            length: Long?,
            sink: suspend (ByteArray, Int) -> Unit,
        ) {
            val bytes = files[path] ?: throw RemoteException(RemoteFailure.GONE, "no such file")
            readLog += offset to length
            if (restUnsupported && offset > 0) throw RemoteException(RemoteFailure.REST_UNSUPPORTED, "REST not supported")
            val fault = if (faults.isEmpty()) null else faults.removeAt(0)
            val end = if (length == null) bytes.size else minOf(bytes.size.toLong(), offset + length).toInt()
            var at = offset.toInt()
            var sent = 0
            reading++
            peakReads = maxOf(peakReads, reading)
            try {
                while (at < end) {
                    if (fault != null && sent >= fault.afterBytes) {
                        fault.error?.let { throw it }
                        return
                    }
                    val n = minOf(CHUNK, end - at)
                    sink(bytes.copyOfRange(at, at + n), n)
                    at += n
                    sent += n
                    if (chunkDelay > Duration.ZERO) delay(chunkDelay) else yield()
                }
            } finally {
                reading--
            }
        }

        override suspend fun close() {
            closed++
            if (connected) {
                connected = false
                active--
            }
        }
    }

    companion object {
        const val CHUNK = 16 * 1024
    }
}
