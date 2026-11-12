package dev.anydm.desktop

import com.github.javakeyring.BackendNotSupportedException
import com.github.javakeyring.Keyring
import com.github.javakeyring.PasswordAccessException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

const val KEYCHAIN_SERVICE = "anydm"
const val NO_KEYCHAIN = "no system keychain"
const val KEYCHAIN_ERROR = "keychain error"

fun proxyAccount(networkId: String) = "proxy:$networkId"

sealed interface SecretResult {
    /** [value] is the secret, or null when there is no entry. It never shows in [toString]. */
    class Ok(
        val value: String?,
    ) : SecretResult {
        override fun toString() = "Ok(value=${if (value == null) "null" else "***"})"

        override fun equals(other: Any?) = other is Ok && other.value == value

        override fun hashCode() = value.hashCode()
    }

    /** [reason] is one of a few fixed texts, never an exception message: those can quote what was being stored. */
    data class Failed(
        val reason: String,
    ) : SecretResult
}

/** Passwords in the OS keychain. Every call may block, so implementations run it off the UI thread themselves. */
interface SecretStore {
    suspend fun get(account: String): SecretResult

    suspend fun set(
        account: String,
        secret: String,
    ): SecretResult

    suspend fun delete(account: String): SecretResult
}

/** For a machine with no usable keychain: every call fails, so the password stays in memory for the session. */
object UnavailableSecretStore : SecretStore {
    override suspend fun get(account: String): SecretResult = SecretResult.Failed(NO_KEYCHAIN)

    override suspend fun set(
        account: String,
        secret: String,
    ): SecretResult = SecretResult.Failed(NO_KEYCHAIN)

    override suspend fun delete(account: String): SecretResult = SecretResult.Failed(NO_KEYCHAIN)
}

/** Secrets held in memory only: the default for [HomeScreen], so a test or a preview never reaches the OS keychain. */
class InMemorySecretStore : SecretStore {
    private val values = ConcurrentHashMap<String, String>()

    override suspend fun get(account: String): SecretResult = SecretResult.Ok(values[account])

    override suspend fun set(
        account: String,
        secret: String,
    ): SecretResult {
        values[account] = secret
        return SecretResult.Ok(secret)
    }

    override suspend fun delete(account: String): SecretResult {
        values.remove(account)
        return SecretResult.Ok(null)
    }
}

/**
 * The blocking keychain calls, so [KeyringSecretStore] can be tested without one. A missing entry makes the library throw
 * [PasswordAccessException]; [KeyringSecretStore] tells that apart from a real failure with [looksAbsent].
 */
interface Vault : AutoCloseable {
    fun get(
        service: String,
        account: String,
    ): String?

    fun set(
        service: String,
        account: String,
        secret: String,
    )

    fun delete(
        service: String,
        account: String,
    )
}

private val WINDOWS_NOT_FOUND_CODE = Regex("""\bError code 1168\b""")

/**
 * The library reports a missing entry only through the text of its exception. These are java-keyring 1.0.4's own texts:
 * - macOS and Linux, on get: "No stored credentials match <service> account: <account>", and on delete the same behind
 *   "No password to delete. ";
 * - Windows: "Password not Found" (no blob) and "Error code 1168" (ERROR_NOT_FOUND).
 * A new library version may reword them; `keychainSpike` checks the two on the real keychain.
 */
internal fun looksAbsent(message: String?): Boolean {
    if (message == null) return false
    return message.startsWith("No stored credentials match ") ||
        message.startsWith("No password to delete. No stored credentials match ") ||
        message == "Password not Found" ||
        WINDOWS_NOT_FOUND_CODE.containsMatchIn(message)
}

private class JavaKeyringVault(
    private val keyring: Keyring,
) : Vault {
    override fun get(
        service: String,
        account: String,
    ): String? = keyring.getPassword(service, account)

    override fun set(
        service: String,
        account: String,
        secret: String,
    ) = keyring.setPassword(service, account, secret)

    override fun delete(
        service: String,
        account: String,
    ) = keyring.deletePassword(service, account)

    override fun close() = keyring.close()
}

/** No usable backend on this machine. */
class NoVaultException : Exception("no system keychain")

private fun openJavaKeyring(): Vault =
    try {
        JavaKeyringVault(Keyring.create())
    } catch (_: BackendNotSupportedException) {
        throw NoVaultException()
    }

/**
 * The OS keychain through java-keyring, under service [KEYCHAIN_SERVICE]. Every call runs on [io] and never throws:
 * a missing backend becomes "no system keychain", anything else "keychain error". Only `main` builds the real one.
 */
class KeyringSecretStore private constructor(
    private val open: () -> Vault,
    private val io: CoroutineDispatcher,
) : SecretStore,
    AutoCloseable {
    companion object {
        /** The real OS keychain. Only [AppStores.real] calls this, and only `main` calls that. */
        fun system(): KeyringSecretStore = KeyringSecretStore(::openJavaKeyring, Dispatchers.IO)

        /** Over any [Vault], for tests: they never get the real keychain from here. */
        internal fun over(
            open: () -> Vault,
            io: CoroutineDispatcher,
        ): KeyringSecretStore = KeyringSecretStore(open, io)
    }

    private var vault: Vault? = null

    /** Closes the vault if a call opened it. [AppStores.flush] calls this once, on the way out. */
    @Synchronized
    override fun close() {
        val v = vault ?: return
        vault = null
        v.close()
    }

    @Synchronized
    private fun vault(): Vault = vault ?: open().also { vault = it }

    private suspend fun run(block: (Vault) -> SecretResult): SecretResult =
        withContext(io) {
            try {
                block(vault())
            } catch (_: NoVaultException) {
                SecretResult.Failed(NO_KEYCHAIN)
            } catch (_: Exception) {
                SecretResult.Failed(KEYCHAIN_ERROR)
            } catch (_: LinkageError) {
                SecretResult.Failed(KEYCHAIN_ERROR)
            }
        }

    /** A missing entry is not a failure: a get reads it as null and a delete does nothing. */
    private inline fun <T> absentIsFine(
        whenAbsent: T,
        call: () -> T,
    ): T =
        try {
            call()
        } catch (e: PasswordAccessException) {
            if (looksAbsent(e.message)) whenAbsent else throw e
        }

    override suspend fun get(account: String): SecretResult =
        run { SecretResult.Ok(absentIsFine(null) { it.get(KEYCHAIN_SERVICE, account) }) }

    override suspend fun set(
        account: String,
        secret: String,
    ): SecretResult =
        run {
            it.set(KEYCHAIN_SERVICE, account, secret)
            SecretResult.Ok(null)
        }

    override suspend fun delete(account: String): SecretResult =
        run {
            absentIsFine(Unit) { it.delete(KEYCHAIN_SERVICE, account) }
            SecretResult.Ok(null)
        }
}
