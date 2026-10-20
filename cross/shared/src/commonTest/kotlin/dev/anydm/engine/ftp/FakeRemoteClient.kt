package dev.anydm.engine.ftp

/**
 * A remote server in memory that every client from [factory] talks to: [files] by path, and hooks to refuse a login or
 * a file. [connects] lists each connect (target and login), [opened] and [closed] count the clients.
 */
internal class FakeRemoteServer {
    val files = mutableMapOf<String, ByteArray>()
    var modified: Long? = 1_700_000_000L
    var reportsSize = true

    /** Thrown by the next connects while set. */
    var connectFailure: RemoteException? = null

    /** Thrown by every stat while set. */
    var statFailure: RemoteException? = null

    val connects = mutableListOf<Pair<RemoteTarget, RemoteLogin>>()
    var opened = 0
        private set
    var closed = 0
        private set

    val factory = RemoteClientFactory { FakeClient() }

    inner class FakeClient : RemoteClient {
        init {
            opened++
        }

        override suspend fun connect(
            target: RemoteTarget,
            login: RemoteLogin,
        ) {
            connects += target to login
            connectFailure?.let { throw it }
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
            val end = if (length == null) bytes.size.toLong() else minOf(bytes.size.toLong(), offset + length)
            var at = offset.toInt()
            while (at < end) {
                val n = minOf(CHUNK, end.toInt() - at)
                sink(bytes.copyOfRange(at, at + n), n)
                at += n
            }
        }

        override suspend fun close() {
            closed++
        }
    }

    companion object {
        const val CHUNK = 16 * 1024
    }
}
