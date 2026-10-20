package dev.anydm.engine.ftp

/**
 * Where the OS keeps secrets (FTP-06): a platform interface, desktop first. An [account] is an opaque name and the
 * secret an opaque string; [get] answers null for an account that has none. Every call throws [KeychainUnavailable]
 * when there is no keychain to talk to or it is locked, so a caller can carry on without it.
 */
interface Keychain {
    suspend fun get(account: String): String?

    suspend fun put(
        account: String,
        secret: String,
    )

    suspend fun remove(account: String)
}

/** No keychain answered: none is installed or running (a Linux box with no Secret Service), or it is locked. */
class KeychainUnavailable(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
