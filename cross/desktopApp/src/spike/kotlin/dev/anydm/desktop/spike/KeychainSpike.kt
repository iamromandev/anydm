package dev.anydm.desktop.spike

import com.github.javakeyring.Keyring
import com.github.javakeyring.PasswordAccessException
import dev.anydm.desktop.looksAbsent
import kotlin.system.exitProcess

private const val SERVICE = "anydm-spike"
private const val ACCOUNT = "spike"

/** The library's text for a missing entry must be one the app recognises, or a missing password would show up as an error. */
private fun checkAbsent(
    what: String,
    block: () -> Unit,
) {
    try {
        block()
    } catch (e: PasswordAccessException) {
        // The message holds only the throwaway service and account, never a secret.
        check(looksAbsent(e.message)) { "looksAbsent rejects the library's text for a $what of a missing entry: \"${e.message}\"" }
    }
}

/** Manual check that the OS keychain works. Prints PASS or FAIL, never the secret. Run by `keychainSpike`, not by `check`. */
fun main() {
    val secret = "spike-" + System.nanoTime()
    var failure: String? = null
    try {
        Keyring.create().use { keyring ->
            keyring.setPassword(SERVICE, ACCOUNT, secret)
            check(keyring.getPassword(SERVICE, ACCOUNT) == secret) { "stored value differs" }
            keyring.deletePassword(SERVICE, ACCOUNT)
            // Now the entry is gone: reading and deleting it again must fail only with a text the app calls "absent".
            var readGone = false
            checkAbsent("get") {
                try {
                    keyring.getPassword(SERVICE, ACCOUNT)
                } catch (e: PasswordAccessException) {
                    readGone = true
                    throw e
                }
            }
            check(readGone) { "entry still present after delete" }
            checkAbsent("delete") { keyring.deletePassword(SERVICE, ACCOUNT) }
        }
    } catch (e: Throwable) {
        failure = if (e is IllegalStateException) e.message ?: e.javaClass.name else e.javaClass.name
    }
    if (failure == null) {
        println("PASS")
    } else {
        println("FAIL $failure")
        exitProcess(1)
    }
}
