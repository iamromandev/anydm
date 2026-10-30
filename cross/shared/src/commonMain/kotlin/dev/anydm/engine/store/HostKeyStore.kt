package dev.anydm.engine.store

/** An SSH server's key: its [keyType] (`ssh-ed25519`) and SHA-256 [fingerprint] (`SHA256:...`). */
data class HostKey(
    val keyType: String,
    val fingerprint: String,
)

/** The host keys SFTP trusts (FTP-04), one per server and port; a host name is matched without regard to case. */
interface HostKeyStore {
    suspend fun get(
        host: String,
        port: Int,
    ): HostKey?

    /** Trusts [key] for the server, replacing any earlier one. */
    suspend fun put(
        host: String,
        port: Int,
        key: HostKey,
    )

    /** Forgets the server's key; true if there was one. */
    suspend fun forget(
        host: String,
        port: Int,
    ): Boolean
}
