package dev.anydm.engine.ftp

import dev.anydm.engine.http.Credentials

/** Where a remote file is: the server and the file's [path] on it (absolute, percent-decoded). */
data class RemoteTarget(
    val scheme: FtpScheme,
    val host: String,
    val port: Int,
    val path: String,
)

/** How a client signs in. A password is the FTP user's or the SSH user's; [Anonymous] is FTP only. */
sealed interface RemoteLogin {
    data class Password(
        val credentials: Credentials,
    ) : RemoteLogin

    data class Key(
        val login: SshKeyLogin,
    ) : RemoteLogin

    data object Anonymous : RemoteLogin
}

/** What a server says about a file: [size] in bytes and [modified] in epoch seconds, each null when it doesn't say. */
data class RemoteStat(
    val size: Long?,
    val modified: Long?,
)

/** Why a client call failed, in the terms the protocol acts on. */
enum class RemoteFailure {
    /** A timeout, a reset or a 4xx reply: try again. */
    TRANSIENT,

    /** The login was refused, or there is none. */
    AUTH,

    /** The file isn't there. */
    GONE,

    /** The server won't take another connection (FTP 421, or 530 "too many"). */
    TOO_MANY_CONNECTIONS,

    /** The server can't start a transfer at an offset (FTP `REST` answered 501 or 502). */
    REST_UNSUPPORTED,

    /** FTPS: the server's certificate isn't trusted, or isn't for this host. */
    CERTIFICATE,

    /** SFTP: the server's key differs from the one saved for it. */
    HOST_KEY_CHANGED,

    /** The proxy can't be used, or refused (NET-15). Nothing was sent to the server. */
    PROXY,

    /** The network the connection was meant to leave by (#544) can't be used. Nothing was sent, and the default route was not tried. */
    BIND,
}

/** A [RemoteClient] call failed. The message never holds a password or a passphrase. */
class RemoteException(
    val failure: RemoteFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * One connection to a server (FTP-01, FTP-04): [connect], then [stat] and [read], then [close]. It is used by one
 * coroutine at a time. Every method throws [RemoteException] for what the server did; cancellation is not caught.
 */
interface RemoteClient {
    suspend fun connect(
        target: RemoteTarget,
        login: RemoteLogin,
    )

    suspend fun stat(path: String): RemoteStat

    /**
     * Streams [length] bytes of [path] from [offset] (to the end of the file when [length] is null) to [sink], which gets
     * a buffer and how much of it is filled; the buffer is the client's again once the sink returns. Fewer bytes than
     * asked for means the file ended there.
     */
    suspend fun read(
        path: String,
        offset: Long,
        length: Long?,
        sink: suspend (ByteArray, Int) -> Unit,
    )

    suspend fun close()
}

/** Makes a client for each worker; the scheme picks the library once the client connects. */
fun interface RemoteClientFactory {
    fun create(): RemoteClient
}
