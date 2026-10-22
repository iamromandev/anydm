package dev.anydm.engine.ftp

import dev.anydm.engine.net.DirectSocketFactory
import dev.anydm.engine.net.ProxyRoute
import dev.anydm.engine.net.ProxySocketFactory
import dev.anydm.engine.net.ProxyUse
import dev.anydm.engine.net.currentProxyRoute
import dev.anydm.engine.net.namedAddress
import dev.anydm.engine.net.proxyFailureOf
import dev.anydm.engine.store.HostKey
import dev.anydm.engine.store.HostKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import java.io.IOException
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

/**
 * SFTP over sshj (FTP-04, FTP-05): password or key login, and a range read straight at an offset, so there is no `REST`
 * to fall back from. The server's host key is trusted on first use through [hostKeys]: the first connection saves its
 * SHA-256 fingerprint, a later different key fails as [RemoteFailure.HOST_KEY_CHANGED] and is not saved. One client is one
 * SSH connection with one SFTP channel, used by one coroutine; blocking calls run on `Dispatchers.IO` and a cancelled read
 * closes the connection under them.
 */
class SshjClient(
    private val hostKeys: HostKeyStore,
) : RemoteClient {
    private var ssh: SSHClient? = null
    private var sftp: SFTPClient? = null

    @Volatile private var changed: Pair<HostKey, HostKey>? = null

    override suspend fun connect(
        target: RemoteTarget,
        login: RemoteLogin,
    ) {
        val client = SSHClient()
        ssh = client
        client.connectTimeout = TIMEOUT_MS
        client.timeout = TIMEOUT_MS
        client.addHostKeyVerifier(trustOnFirstUse())
        val host = target.host.removePrefix("[").removeSuffix("]")
        val route = currentProxyRoute("sftp://${target.host}:${target.port}", ProxyUse.SFTP)
        if (route is ProxyRoute.Blocked) throw RemoteException(RemoteFailure.PROXY, route.reason)
        around(host, target.port) {
            if (route is ProxyRoute.Via) {
                // The proxy looks the name up; the address keeps it, for the host key too.
                client.socketFactory = ProxySocketFactory(route.endpoint)
                client.connect(namedAddress(host), target.port)
            } else {
                client.socketFactory = DirectSocketFactory
                client.connect(host, target.port)
            }
            authenticate(client, login)
            sftp = client.newSFTPClient()
        }
    }

    override suspend fun stat(path: String): RemoteStat {
        val channel = channel()
        return around(null, 0) {
            val file = channel.open(path)
            try {
                val attributes = file.fetchAttributes()
                RemoteStat(attributes.size, attributes.mtime)
            } finally {
                file.close()
            }
        }
    }

    override suspend fun read(
        path: String,
        offset: Long,
        length: Long?,
        sink: suspend (ByteArray, Int) -> Unit,
    ) {
        val channel = channel()
        val client = ssh
        coroutineScope {
            var finished = false
            // Blocking reads don't see coroutine cancellation: closing the connection under them is what stops them.
            val guard =
                launch {
                    try {
                        awaitCancellation()
                    } finally {
                        if (!finished) runCatching { client?.disconnect() }
                    }
                }
            try {
                around(null, 0) { readBlocking(channel, path, offset, length, sink) }
            } finally {
                finished = true
                guard.cancel()
            }
        }
    }

    override suspend fun close() {
        val client = ssh ?: return
        ssh = null
        sftp = null
        withContext(NonCancellable + Dispatchers.IO) { runCatching { client.disconnect() } }
    }

    private suspend fun readBlocking(
        channel: SFTPClient,
        path: String,
        offset: Long,
        length: Long?,
        sink: suspend (ByteArray, Int) -> Unit,
    ) {
        val file = channel.open(path)
        try {
            val stream = file.ReadAheadRemoteFileInputStream(READ_AHEAD, offset)
            val buffer = ByteArray(CHUNK)
            var left = length ?: Long.MAX_VALUE
            while (left > 0) {
                val n = stream.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (n < 0) break
                sink(buffer, n)
                left -= n
            }
        } finally {
            runCatching { file.close() }
        }
    }

    private fun authenticate(
        client: SSHClient,
        login: RemoteLogin,
    ) {
        when (login) {
            RemoteLogin.Anonymous -> {
                throw RemoteException(RemoteFailure.AUTH, "SFTP needs a login")
            }

            is RemoteLogin.Password -> {
                client.authPassword(login.credentials.user, login.credentials.password)
            }

            is RemoteLogin.Key -> {
                val keys =
                    try {
                        val passphrase = login.login.passphrase
                        if (passphrase == null) client.loadKeys(login.login.keyPath) else client.loadKeys(login.login.keyPath, passphrase)
                    } catch (e: Exception) {
                        throw RemoteException(RemoteFailure.AUTH, "could not read the key file ${login.login.keyPath}", e)
                    }
                client.authPublickey(login.login.user, keys)
            }
        }
    }

    // Trust on first use: the first key seen for host:port is saved, the same key is fine, any other is refused.
    private fun trustOnFirstUse() =
        object : HostKeyVerifier {
            override fun verify(
                hostname: String,
                port: Int,
                key: PublicKey,
            ): Boolean {
                val seen = HostKey(KeyType.fromKey(key).toString(), fingerprintOf(key))
                val saved = runBlocking { hostKeys.get(hostname, port) }
                if (saved == null) {
                    runBlocking { hostKeys.put(hostname, port, seen) }
                    return true
                }
                if (saved == seen) return true
                changed = saved to seen
                return false
            }

            override fun findExistingAlgorithms(
                hostname: String,
                port: Int,
            ): List<String> = runBlocking { hostKeys.get(hostname, port) }?.let { listOf(it.keyType) }.orEmpty()
        }

    // Runs blocking [block] on the IO threads and turns what sshj throws into the failures the protocol acts on.
    private suspend fun <T> around(
        host: String?,
        port: Int,
        block: suspend () -> T,
    ): T =
        try {
            withContext(Dispatchers.IO) { block() }
        } catch (e: RemoteException) {
            throw e
        } catch (e: IOException) {
            throw failureOf(e, host, port)
        }

    private fun failureOf(
        e: IOException,
        host: String?,
        port: Int,
    ): RemoteException {
        changed?.let { (saved, seen) ->
            return RemoteException(
                RemoteFailure.HOST_KEY_CHANGED,
                "the host key of ${host ?: "the server"}${if (port > 0) ":$port" else ""} changed: " +
                    "saved ${saved.keyType} ${saved.fingerprint}, now ${seen.keyType} ${seen.fingerprint}",
                e,
            )
        }
        proxyFailureOf(e)?.let { return RemoteException(RemoteFailure.PROXY, it.message ?: "the proxy refused", e) }
        return when {
            e is UserAuthException -> {
                RemoteException(RemoteFailure.AUTH, "the SSH login was refused", e)
            }

            e is SFTPException && e.statusCode == Response.StatusCode.NO_SUCH_FILE -> {
                RemoteException(RemoteFailure.GONE, "no such file", e)
            }

            e is SFTPException && e.statusCode == Response.StatusCode.PERMISSION_DENIED -> {
                RemoteException(RemoteFailure.AUTH, "permission denied", e)
            }

            else -> {
                RemoteException(RemoteFailure.TRANSIENT, "SSH connection failed: ${e.message?.take(160)}", e)
            }
        }
    }

    private fun channel(): SFTPClient = sftp ?: throw IllegalStateException("not connected")

    private companion object {
        const val CHUNK = 32 * 1024
        const val READ_AHEAD = 16
        const val TIMEOUT_MS = 30_000
    }
}

/** The OpenSSH-style fingerprint of [key]: `SHA256:` and the unpadded base64 of the SHA-256 of its wire encoding. */
internal fun fingerprintOf(key: PublicKey): String {
    val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
    val digest = MessageDigest.getInstance("SHA-256").digest(blob)
    return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
}
