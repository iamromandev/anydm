package dev.anydm.engine.ftp

/** A keychain in a map. [failing] makes every call throw [KeychainUnavailable], as a machine with no keychain does. */
internal class FakeKeychain : Keychain {
    val entries = mutableMapOf<String, String>()
    var failing = false
    var gets = 0
        private set

    override suspend fun get(account: String): String? {
        gets++
        if (failing) throw KeychainUnavailable("no keychain")
        return entries[account]
    }

    override suspend fun put(
        account: String,
        secret: String,
    ) {
        if (failing) throw KeychainUnavailable("no keychain")
        entries[account] = secret
    }

    override suspend fun remove(account: String) {
        if (failing) throw KeychainUnavailable("no keychain")
        entries.remove(account)
    }
}
