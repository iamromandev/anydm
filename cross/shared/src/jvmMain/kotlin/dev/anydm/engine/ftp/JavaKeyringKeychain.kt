package dev.anydm.engine.ftp

import com.github.javakeyring.BackendNotSupportedException
import com.github.javakeyring.Keyring
import com.github.javakeyring.PasswordAccessException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * The OS keychain through java-keyring (FTP-06): Keychain on macOS, Credential Manager on Windows, the Secret Service on
 * Linux. Everything is stored under [service]. A machine with no keychain (a Linux box with no Secret Service, a locked
 * one) makes each call throw [KeychainUnavailable]; the backend is looked for again on the next call, not remembered as
 * missing. [create] makes the java-keyring backend, and is replaceable in tests.
 */
class JavaKeyringKeychain(
    private val service: String = "anydm",
    private val create: () -> Keyring = { Keyring.create() },
) : Keychain {
    private val lock = Mutex()
    private var keyring: Keyring? = null

    override suspend fun get(account: String): String? =
        use { keyring ->
            try {
                keyring.getPassword(service, account)
            } catch (e: PasswordAccessException) {
                if (missing(e)) null else throw KeychainUnavailable("the keychain refused a read", e)
            }
        }

    override suspend fun put(
        account: String,
        secret: String,
    ) = use { keyring ->
        try {
            keyring.setPassword(service, account, secret)
        } catch (e: PasswordAccessException) {
            throw KeychainUnavailable("the keychain refused a write", e)
        }
    }

    override suspend fun remove(account: String) =
        use { keyring ->
            try {
                keyring.deletePassword(service, account)
            } catch (e: PasswordAccessException) {
                if (!missing(e)) throw KeychainUnavailable("the keychain refused a delete", e)
            }
        }

    // Runs [block] on an IO thread against the backend, made on first use (and again if the last try found none). Whatever the
    // backend does wrong (no backend, a native library that won't load, a D-Bus error) is KeychainUnavailable.
    private suspend fun <T> use(block: (Keyring) -> T): T =
        withContext(Dispatchers.IO) {
            try {
                val backend = lock.withLock { keyring ?: create().also { keyring = it } }
                block(backend)
            } catch (e: KeychainUnavailable) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackendNotSupportedException) {
                throw KeychainUnavailable("this machine has no keychain java-keyring can use", e)
            } catch (e: Exception) {
                throw KeychainUnavailable("the keychain failed: ${e::class.simpleName}", e)
            } catch (e: LinkageError) {
                throw KeychainUnavailable("the keychain's native library would not load: ${e.message}", e)
            }
        }

    // java-keyring has no "not found" type: each OS says it in its own words.
    private fun missing(e: PasswordAccessException): Boolean {
        val text = e.message.orEmpty().lowercase()
        return "not found" in text || "could not be found" in text || "no such" in text || "not exist" in text || "1168" in text
    }
}
