package dev.anydm.engine.ftp

import dev.anydm.engine.http.CredentialStore
import dev.anydm.engine.http.Credentials
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** An SFTP login by private key (FTP-04): the key stays in the file at [keyPath]; the engine never copies it. */
data class SshKeyLogin(
    val user: String,
    val keyPath: String,
    val passphrase: String?,
) {
    override fun toString(): String = "SshKeyLogin(user=$user, keyPath=$keyPath, passphrase=${if (passphrase == null) "none" else "***"})"
}

/** Takes in the logins found in URLs or given by the app. */
interface CredentialWriter {
    /** True when [credentials] reached the keychain; false when it failed and the login is held in memory for this run only. */
    suspend fun save(
        origin: String,
        credentials: Credentials,
    ): Boolean

    /** The same for a key login. */
    suspend fun saveKey(
        origin: String,
        login: SshKeyLogin,
    ): Boolean
}

/** Where SFTP finds a key login for an origin. */
interface KeyLogins {
    suspend fun keyFor(origin: String): SshKeyLogin?
}

/**
 * Logins per origin (`ftp://host:21`, `https://host:443`) kept in the OS [keychain] (FTP-06), read once and then from
 * memory. It is the [CredentialStore] HTTP auth asks too. With no keychain a login lives in memory for this run: [save]
 * says so, and a read that fails is not remembered as "no login", so a keychain that comes back is asked again.
 */
class KeychainCredentials(
    private val keychain: Keychain,
) : CredentialStore,
    CredentialWriter,
    KeyLogins {
    private val lock = Mutex()
    private val passwords = mutableMapOf<String, Credentials>()
    private val keys = mutableMapOf<String, SshKeyLogin>()

    override suspend fun credentialsFor(origin: String): Credentials? =
        lock.withLock {
            passwords[origin]
                ?: read(passwordAccount(origin))?.let(::passwordOf)?.also { passwords[origin] = it }
        }

    override suspend fun keyFor(origin: String): SshKeyLogin? =
        lock.withLock {
            keys[origin] ?: read(keyAccount(origin))?.let(::keyOf)?.also { keys[origin] = it }
        }

    override suspend fun save(
        origin: String,
        credentials: Credentials,
    ): Boolean =
        lock.withLock {
            passwords[origin] = credentials
            write(passwordAccount(origin), jsonOf("user" to credentials.user, "password" to credentials.password))
        }

    override suspend fun saveKey(
        origin: String,
        login: SshKeyLogin,
    ): Boolean =
        lock.withLock {
            keys[origin] = login
            write(keyAccount(origin), jsonOf("user" to login.user, "keyPath" to login.keyPath, "passphrase" to login.passphrase))
        }

    private suspend fun read(account: String): String? =
        try {
            keychain.get(account)
        } catch (_: KeychainUnavailable) {
            null
        }

    private suspend fun write(
        account: String,
        value: String,
    ): Boolean =
        try {
            keychain.put(account, value)
            true
        } catch (_: KeychainUnavailable) {
            false
        }

    private fun passwordAccount(origin: String) = "anydm:$origin"

    private fun keyAccount(origin: String) = "anydm-key:$origin"

    private fun jsonOf(vararg fields: Pair<String, String?>): String =
        JsonObject(fields.filter { it.second != null }.associate { it.first to JsonPrimitive(it.second) }).toString()

    private fun objectOf(text: String): JsonObject? = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()

    private fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun passwordOf(text: String): Credentials? {
        val json = objectOf(text) ?: return null
        return runCatching { Credentials(json.text("user") ?: return null, json.text("password") ?: return null) }.getOrNull()
    }

    private fun keyOf(text: String): SshKeyLogin? {
        val json = objectOf(text) ?: return null
        return SshKeyLogin(json.text("user") ?: return null, json.text("keyPath") ?: return null, json.text("passphrase"))
    }
}
