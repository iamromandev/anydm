package dev.anydm.engine.ftp

import dev.anydm.engine.net.BoundSocketFactories
import dev.anydm.engine.store.HostKeyStore

/** Makes the client for a scheme once it connects: Commons Net for FTP, FTPS and FTPES, sshj for SFTP. */
class DefaultRemoteClients(
    private val hostKeys: HostKeyStore,
    private val trust: TrustConfig = TrustConfig.Jvm,
    /** Sockets tied to a network, for a connection made inside one (BND-02); without it such a connection fails. */
    private val bound: BoundSocketFactories? = null,
) : RemoteClientFactory {
    override fun create(): RemoteClient = ByScheme()

    private inner class ByScheme : RemoteClient {
        private var inner: RemoteClient? = null

        override suspend fun connect(
            target: RemoteTarget,
            login: RemoteLogin,
        ) {
            val client = if (target.scheme == FtpScheme.SFTP) SshjClient(hostKeys, bound) else CommonsNetClient(trust, bound)
            inner = client
            client.connect(target, login)
        }

        override suspend fun stat(path: String): RemoteStat = connected().stat(path)

        override suspend fun read(
            path: String,
            offset: Long,
            length: Long?,
            sink: suspend (ByteArray, Int) -> Unit,
        ) = connected().read(path, offset, length, sink)

        override suspend fun close() {
            inner?.close()
        }

        private fun connected(): RemoteClient = inner ?: throw IllegalStateException("not connected")
    }
}
