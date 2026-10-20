package dev.anydm.engine.ftp

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
import java.security.KeyStore
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLKeyException
import javax.net.ssl.SSLPeerUnverifiedException
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
 * FTP, FTPS (implicit) and FTPES (explicit) over Apache Commons Net (FTP-01 to FTP-03): passive mode, binary type, UTF-8
 * names, and a range read that starts with `REST` and stops by closing the data connection. TLS checks the server's
 * certificate and its host name (the JVM's trust store, or [trust]). One client is one connection, used by one
 * coroutine; the blocking socket calls run on `Dispatchers.IO` and a cancelled read closes the socket under them.
 */
class CommonsNetClient(
    private val trust: TrustConfig = TrustConfig.Jvm,
) : RemoteClient {
    private var ftp: FTPClient? = null

    override suspend fun connect(
        target: RemoteTarget,
        login: RemoteLogin,
    ) {
        val client = clientFor(target.scheme)
        ftp = client
        around(client) {
            val host = target.host.removePrefix("[").removeSuffix("]")
            client.connect(host, target.port)
            expectGreeting(client)
            if (client is FTPSClient) {
                client.execPBSZ(0)
                client.execPROT("P")
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
            throw RemoteException(RemoteFailure.TRANSIENT, "connection failed: ${e.message?.take(160)}", e)
        }

    private fun connected(): FTPClient = ftp ?: throw IllegalStateException("not connected")

    private fun clientFor(scheme: FtpScheme): FTPClient {
        val client =
            when (scheme) {
                FtpScheme.FTP -> FTPClient()
                FtpScheme.FTPS -> secured(FTPSClient(true))
                FtpScheme.FTPES -> secured(FTPSClient(false))
                FtpScheme.SFTP -> throw IllegalArgumentException("sftp is not FTP")
            }
        client.controlEncoding = "UTF-8"
        client.connectTimeout = TIMEOUT_MS
        client.defaultTimeout = TIMEOUT_MS
        client.setDataTimeout(java.time.Duration.ofMillis(TIMEOUT_MS.toLong()))
        return client
    }

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
