package dev.anydm.engine.torrent

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One open connection to a peer. */
interface PeerConn {
    /** Reads up to [len] bytes into [buf] at [off]; -1 at the end of the stream. Fails with an [IOException]. */
    suspend fun read(
        buf: ByteArray,
        off: Int,
        len: Int,
    ): Int

    suspend fun write(bytes: ByteArray)

    /**
     * Closes the connection. Safe to call again and from any thread; a read or write that is blocked on it fails with an
     * [IOException]. This is what cancellation uses to stop blocking IO, which coroutine cancellation alone does not reach.
     */
    fun close()
}

interface PeerTransport {
    /**
     * Opens a connection to [addr], waiting at most [timeoutMs]. Cancelling the call abandons the attempt and closes the socket.
     * Fails with an [IOException].
     */
    suspend fun connect(
        addr: PeerAddr,
        timeoutMs: Int,
    ): PeerConn
}

/**
 * TCP with blocking sockets on [pool], which needs a thread for every connection that is waiting to read: the default is sized for
 * [MAX_PEERS_TOTAL] connections reading and as many writing, plus some for connects in progress.
 */
class TcpPeerTransport(
    private val pool: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2 * MAX_PEERS_TOTAL + 32),
) : PeerTransport {
    private val scope = CoroutineScope(SupervisorJob() + pool)

    override suspend fun connect(
        addr: PeerAddr,
        timeoutMs: Int,
    ): PeerConn =
        suspendCancellableCoroutine { cont ->
            val socket = Socket()
            // closing a socket that is connecting aborts the connect: this is how a cancelled attempt stops
            cont.invokeOnCancellation { runCatching { socket.close() } }
            scope.launch {
                try {
                    socket.connect(InetSocketAddress(addr.host, addr.port), timeoutMs)
                    socket.tcpNoDelay = true
                    cont.resume(TcpPeerConn(socket, pool)) { _, conn, _ -> conn.close() }
                } catch (e: IOException) {
                    runCatching { socket.close() }
                    cont.resumeWithException(e)
                } catch (e: IllegalArgumentException) {
                    runCatching { socket.close() }
                    cont.resumeWithException(IOException("Bad address", e))
                }
            }
        }
}

internal class TcpPeerConn(
    private val socket: Socket,
    private val pool: CoroutineDispatcher,
) : PeerConn {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    override suspend fun read(
        buf: ByteArray,
        off: Int,
        len: Int,
    ): Int = withContext(pool) { input.read(buf, off, len) }

    override suspend fun write(bytes: ByteArray) =
        withContext(pool) {
            output.write(bytes)
            output.flush()
        }

    override fun close() {
        runCatching { socket.close() }
    }
}
