package dev.anydm.engine.ftp

/** A keychain in a map. [failing] makes every call throw [KeychainUnavailable], as a machine with no keychain does. */
internal class FakeKeychain : Keychain {
    val entries = mutableMapOf<String, String>()
    var failing = false

    /** Thrown by every call while set, whatever its type (a broken native library is not a KeychainUnavailable). */
    var failure: Throwable? = null
    var gets = 0
        private set

    override suspend fun get(account: String): String? {
        gets++
        failure?.let { throw it }
        if (failing) throw KeychainUnavailable("no keychain")
        return entries[account]
    }

    override suspend fun put(
        account: String,
        secret: String,
    ) {
        failure?.let { throw it }
        if (failing) throw KeychainUnavailable("no keychain")
        entries[account] = secret
    }

    override suspend fun remove(account: String) {
        failure?.let { throw it }
        if (failing) throw KeychainUnavailable("no keychain")
        entries.remove(account)
    }
}
