package dev.anydm.engine.ftp

import dev.anydm.engine.net.BoundSocketFactories
import dev.anydm.engine.net.DirectSocketFactory
import dev.anydm.engine.net.ProxyRoute
import dev.anydm.engine.net.ProxySocketFactory
import dev.anydm.engine.net.ProxyUse
import dev.anydm.engine.net.bindFailureOf
import dev.anydm.engine.net.currentBoundNetwork
import dev.anydm.engine.net.currentProxyRoute
import dev.anydm.engine.net.namedAddress
import dev.anydm.engine.net.proxyFailureOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPConnectionClosedException
import org.apache.commons.net.ftp.FTPSClient
import org.apache.commons.net.util.TrustManagerUtils
import java.io.FileInputStream
import java.io.IOException
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLKeyException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** Which certificates an FTPS server may present: the JVM's trusted ones, or (in tests) those of one keystore. */
class TrustConfig private constructor(
    private val keyStore: KeyStore?,
) {
    internal fun manager(): X509TrustManager = TrustManagerUtils.getDefaultTrustManager(keyStore)

    companion object {
        /** What the JVM trusts: the same certificates a browser-less Java program trusts. */
        val Jvm = TrustConfig(null)

        /** The certificates in the JKS keystore at [path]; for tests with a self-signed server. */
        fun fromKeystore(
            path: String,
            password: String,
        ): TrustConfig {
            val store = KeyStore.getInstance("JKS")
            FileInputStream(path).use { store.load(it, password.toCharArray()) }
            return TrustConfig(store)
        }
    }
}

/**
 * Why a TLS error happened: a refused handshake or certificate is [RemoteFailure.CERTIFICATE] (the login can't succeed until
 * the server or the trust changes); anything else, such as a connection reset in the middle of a transfer, is
 * [RemoteFailure.TRANSIENT] and resumes like a plain FTP drop.
 */
internal fun tlsFailureOf(e: SSLException): RemoteFailure {
    val refused =
        generateSequence<Throwable>(e) { it.cause }.take(8).any {
            it is SSLHandshakeException || it is SSLPeerUnverifiedException || it is SSLKeyException || it is CertificateException
        }
    return if (refused) RemoteFailure.CERTIFICATE else RemoteFailure.TRANSIENT
}

/**
 * An FTPS client for a connection that may go through a proxy (NET-15).
 *
 * It knows the name of the server it reached through a proxy, where the address it connects to has none to give: TLS
 * needs the name for the server's certificate and for SNI.
 *
 * It also keeps its data connections on the socket factory it is given. `execPROT("P")` swaps that factory for one that
 * makes plain unconnected TLS sockets, which connect on their own, around any proxy and through the JVM's proxy
 * selector. [protectData] sends PROT P and puts the given factory back, and every data connection is then made on it and
 * has TLS layered over it here, as Commons Net would have done.
 */
internal class NamedFtpsClient(
    implicit: Boolean,
    private val tls: SSLContext,
) : FTPSClient(implicit) {
    var named: String? = null
    private var dataProtected = false

    override fun _connectAction_() {
        named?.let { _hostname_ = it }
        super._connectAction_()
    }

    /** PROT P, and data connections on [plain] with TLS over them. */
    fun protectData(plain: SocketFactory) {
        execPROT("P")
        setSocketFactory(plain)
        dataProtected = true
    }

    override fun _openDataConnection_(
        command: String?,
        arg: String?,
    ): Socket? {
        val plain = super._openDataConnection_(command, arg)
        if (!dataProtected || plain == null || plain is SSLSocket) return plain
        try {
            val host = named ?: _hostname_ ?: remoteAddress.hostAddress
            val secured = tls.socketFactory.createSocket(plain, host, plain.port, true) as SSLSocket
            secured.useClientMode = true
            secured.enableSessionCreation = isCreation
            getSuites()?.let { secured.enabledCipherSuites = it }
            getProtocols()?.let { secured.enabledProtocols = it }
            // The data connection is to the same server as the control one: its certificate has to be for that name.
            if (isEndpointCheckingEnabled) {
                secured.sslParameters =
                    secured.sslParameters.also { it.endpointIdentificationAlgorithm = "HTTPS" }
            }
            secured.startHandshake()
            return secured
        } catch (e: IOException) {
            runCatching { plain.close() }
            throw e
        }
    }
}

/**
 * FTP, FTPS (implicit) and FTPES (explicit) over Apache Commons Net (FTP-01 to FTP-03): passive mode, binary type, UTF-8
 * names, and a range read that starts with `REST` and stops by closing the data connection. TLS checks the server's
 * certificate and its host name (the JVM's trust store, or [trust]). One client is one connection, used by one
 * coroutine; the blocking socket calls run on `Dispatchers.IO` and a cancelled read closes the socket under them.
 */
class CommonsNetClient(
    private val trust: TrustConfig = TrustConfig.Jvm,
    /** Sockets tied to a network (BND-02); without it a connection made inside a bound network fails. */
    private val bound: BoundSocketFactories? = null,
) : RemoteClient {
    private var ftp: FTPClient? = null

    override suspend fun connect(
        target: RemoteTarget,
        login: RemoteLogin,
    ) {
        val client = clientFor(target.scheme)
        ftp = client
        val route = currentProxyRoute("${target.scheme.name.lowercase()}://${target.host}:${target.port}", ProxyUse.FTP)
        if (route is ProxyRoute.Blocked) throw RemoteException(RemoteFailure.PROXY, route.reason)
        around(client) {
            val host = target.host.removePrefix("[").removeSuffix("]")
            val plain: SocketFactory
            if (route is ProxyRoute.Via) {
                // The proxy looks the name up. A passive reply's private address is left as the server said it: the
                // workaround would send the data connection to the proxy's own address.
                plain = ProxySocketFactory(route.endpoint)
                client.setSocketFactory(plain)
                client.setPassiveNatWorkaroundStrategy(null)
                // The data connection's far end is checked against the control connection's address, which here has no
                // IP to compare: the proxy is what both reach.
                client.isRemoteVerificationEnabled = false
                (client as? NamedFtpsClient)?.named = host
                client.connect(namedAddress(host), target.port)
            } else {
                // Not the JVM's default sockets: they ask its proxy selector, and Direct has to mean direct. Inside a bound
                // network the control and every data connection leave by that network.
                val network = currentBoundNetwork()
                plain =
                    if (network == null) {
                        DirectSocketFactory
                    } else {
                        (
                            bound ?: throw RemoteException(
                                RemoteFailure.BIND,
                                "this machine can't bind a connection to a network",
                            )
                        ).factoryFor(network)
                    }
                client.setSocketFactory(plain)
                client.connect(host, target.port)
            }
            expectGreeting(client)
            if (client is NamedFtpsClient) {
                client.execPBSZ(0)
                client.protectData(plain)
            }
            val (user, password) =
                when (login) {
                    RemoteLogin.Anonymous -> "anonymous" to "anydm@"
                    is RemoteLogin.Password -> login.credentials.user to login.credentials.password
                    is RemoteLogin.Key -> throw RemoteException(RemoteFailure.AUTH, "a key login is for SFTP, not ${target.scheme}")
                }
            if (!client.login(user, password)) throw failureOf(client)
            client.enterLocalPassiveMode()
            if (!client.setFileType(FTP.BINARY_FILE_TYPE)) throw failureOf(client)
        }
    }

    override suspend fun stat(path: String): RemoteStat {
        val client = connected()
        return around(client) {
            val size = client.getSize(path)?.trim()?.toLongOrNull()
            val sizeCode = client.replyCode
            val modified = client.mdtmInstant(path)?.epochSecond
            val modifiedCode = client.replyCode
            // A server that has neither command says 500 or 502; one that says 550 to both has no such file.
            if (size == null && modified == null && sizeCode == NOT_FOUND && modifiedCode == NOT_FOUND) {
                throw RemoteException(RemoteFailure.GONE, "no such file")
            }
            RemoteStat(size, modified)
        }
    }

    override suspend fun read(
        path: String,
        offset: Long,
        length: Long?,
        sink: suspend (ByteArray, Int) -> Unit,
    ) {
        val client = connected()
        coroutineScope {
            var finished = false
            // Blocking socket reads don't see coroutine cancellation: closing the socket under them is what stops them.
            val guard =
                launch {
                    try {
                        awaitCancellation()
                    } finally {
                        if (!finished) runCatching { client.disconnect() }
                    }
                }
            try {
                around(client) { readBlocking(client, path, offset, length, sink) }
            } finally {
                finished = true
                guard.cancel()
            }
        }
    }

    override suspend fun close() {
        val client = ftp ?: return
        ftp = null
        withContext(NonCancellable + Dispatchers.IO) { runCatching { if (client.isConnected) client.disconnect() } }
    }

    private suspend fun readBlocking(
        client: FTPClient,
        path: String,
        offset: Long,
        length: Long?,
        sink: suspend (ByteArray, Int) -> Unit,
    ) {
        client.restartOffset = offset
        val stream = client.retrieveFileStream(path) ?: throw failureOf(client, rest = offset > 0)
        var reachedEnd = false
        try {
            val buffer = ByteArray(CHUNK)
            var left = length ?: Long.MAX_VALUE
            while (left > 0) {
                val n = stream.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (n < 0) {
                    reachedEnd = true
                    break
                }
                sink(buffer, n)
                left -= n
            }
        } finally {
            stream.close()
        }
        // After a bounded read the server is still sending; the caller closes the connection, so the reply isn't waited for.
        if (reachedEnd) client.completePendingCommand()
    }

    // Runs blocking [block] on the IO threads and turns what the socket throws into the failures the protocol acts on.
    private suspend fun <T> around(
        client: FTPClient,
        block: suspend () -> T,
    ): T =
        try {
            withContext(Dispatchers.IO) { block() }
        } catch (e: RemoteException) {
            throw e
        } catch (e: SSLException) {
            val failure = tlsFailureOf(e)
            val what = if (failure == RemoteFailure.CERTIFICATE) "the server's certificate was refused" else "the TLS connection broke"
            throw RemoteException(failure, "$what: ${e.message?.take(160)}", e)
        } catch (e: FTPConnectionClosedException) {
            // Commons Net throws this for a 421 reply: the server is full or going down, and has closed the connection.
            val failure = if (client.replyCode == SERVICE_NOT_AVAILABLE) RemoteFailure.TOO_MANY_CONNECTIONS else RemoteFailure.TRANSIENT
            throw RemoteException(failure, "FTP connection closed by the server (${client.replyCode})", e)
        } catch (e: IOException) {
            val refusal = proxyFailureOf(e)
            if (refusal != null) throw RemoteException(RemoteFailure.PROXY, refusal.message ?: "the proxy refused", e)
            val unbound = bindFailureOf(e)
            if (unbound != null) throw RemoteException(RemoteFailure.BIND, unbound.message ?: "the network can't be used", e)
            throw RemoteException(RemoteFailure.TRANSIENT, "connection failed: ${e.message?.take(160)}", e)
        }

    private fun connected(): FTPClient = ftp ?: throw IllegalStateException("not connected")

    private fun clientFor(scheme: FtpScheme): FTPClient {
        val client =
            when (scheme) {
                FtpScheme.FTP -> FTPClient()
                FtpScheme.FTPS -> secured(NamedFtpsClient(true, tlsContext()))
                FtpScheme.FTPES -> secured(NamedFtpsClient(false, tlsContext()))
                FtpScheme.SFTP -> throw IllegalArgumentException("sftp is not FTP")
            }
        client.controlEncoding = "UTF-8"
        client.connectTimeout = TIMEOUT_MS
        client.defaultTimeout = TIMEOUT_MS
        client.setDataTimeout(java.time.Duration.ofMillis(TIMEOUT_MS.toLong()))
        return client
    }

    // The context for the data connections, trusting what the control connection trusts.
    private fun tlsContext(): SSLContext = SSLContext.getInstance("TLS").also { it.init(null, arrayOf(trust.manager()), null) }

    // TLS: this trust store, and the certificate has to be for the host that was asked for.
    private fun secured(client: FTPSClient): FTPSClient =
        client.also {
            it.trustManager = trust.manager()
            it.isEndpointCheckingEnabled = true
        }

    private fun expectGreeting(client: FTPClient) {
        val code = client.replyCode
        if (code !in 200..299) throw failureOf(client)
    }

    // The failure for the client's last reply: [rest] when it answered a REST that came before the transfer.
    private fun failureOf(
        client: FTPClient,
        rest: Boolean = false,
    ): RemoteException {
        val code = client.replyCode
        val text =
            client.replyString
                .orEmpty()
                .trim()
                .lines()
                .firstOrNull()
                .orEmpty()
                .take(160)
        if (code == SESSION_REUSE_REQUIRED || text.contains("session reuse", ignoreCase = true)) {
            // Commons Net can't resume the control connection's TLS session on a data connection, which some servers
            // (vsftpd by default) insist on. Retrying can't help, so it fails like a refused certificate.
            return RemoteException(
                RemoteFailure.CERTIFICATE,
                "the server requires TLS session reuse, which this client can't do (FTP reply $code)",
            )
        }
        val failure =
            when {
                rest && (code == 501 || code == 502) -> RemoteFailure.REST_UNSUPPORTED
                code == 421 -> RemoteFailure.TOO_MANY_CONNECTIONS
                code == 530 && text.contains("too many", ignoreCase = true) -> RemoteFailure.TOO_MANY_CONNECTIONS
                code == 530 || code == 331 || code == 332 || code == 532 -> RemoteFailure.AUTH
                code == NOT_FOUND -> RemoteFailure.GONE
                else -> RemoteFailure.TRANSIENT
            }
        return RemoteException(failure, "FTP reply $code: $text")
    }

    private companion object {
        const val CHUNK = 16 * 1024
        const val TIMEOUT_MS = 30_000
        const val NOT_FOUND = 550
        const val SERVICE_NOT_AVAILABLE = 421
        const val SESSION_REUSE_REQUIRED = 522
    }
}
