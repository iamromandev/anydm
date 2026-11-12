package dev.anydm.desktop

import com.github.javakeyring.PasswordAccessException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Only fakes here: nothing in this file opens the real keychain. */
class SecretStoreTest {
    private val secret = "s3cret-pass-word"

    private class FakeVault(
        var failWith: Throwable? = null,
    ) : Vault {
        val entries = mutableMapOf<String, String>()
        val calls = mutableListOf<String>()
        val threads = mutableListOf<Thread>()

        private fun enter(call: String) {
            calls += call
            threads += Thread.currentThread()
            failWith?.let { throw it }
        }

        override fun get(
            service: String,
            account: String,
        ): String? {
            enter("get $service/$account")
            return entries["$service/$account"]
        }

        override fun set(
            service: String,
            account: String,
            secret: String,
        ) {
            enter("set $service/$account")
            entries["$service/$account"] = secret
        }

        override fun delete(
            service: String,
            account: String,
        ) {
            enter("delete $service/$account")
            entries.remove("$service/$account")
        }

        var closed = 0

        override fun close() {
            closed++
        }
    }

    private fun store(vault: FakeVault) = KeyringSecretStore.over({ vault }, Dispatchers.IO)

    @Test
    fun aSecretIsStoredUnderTheAnydmServiceAndTheAccount() =
        runBlocking {
            val vault = FakeVault()
            val s = store(vault)
            assertEquals(SecretResult.Ok(null), s.set("proxy:usb", secret))
            assertEquals(mapOf("anydm/proxy:usb" to secret), vault.entries)
            assertEquals(SecretResult.Ok(secret), s.get("proxy:usb"))
            assertEquals(SecretResult.Ok(null), s.delete("proxy:usb"))
            assertEquals(SecretResult.Ok(null), s.get("proxy:usb"))
        }

    @Test
    fun theVaultIsOnlyEverCalledOffTheCallingThread() =
        runBlocking {
            val vault = FakeVault()
            val s = store(vault)
            s.set("a", secret)
            s.get("a")
            s.delete("a")
            assertEquals(3, vault.threads.size)
            val caller = Thread.currentThread()
            assertTrue(vault.threads.none { it === caller })
            assertTrue(vault.threads.all { it.name.startsWith("DefaultDispatcher-worker") }, vault.threads.map { it.name }.toString())
        }

    @Test
    fun anyExceptionBecomesAKeychainErrorThatNeverQuotesIt() =
        runBlocking {
            val vault = FakeVault(RuntimeException("could not store $secret"))
            val s = store(vault)
            for (r in listOf(s.get("a"), s.set("a", secret), s.delete("a"))) {
                assertEquals(SecretResult.Failed("keychain error"), r)
                assertFalse(r.toString().contains(secret))
            }
        }

    @Test
    fun aMissingNativeLibraryIsAKeychainErrorToo() =
        runBlocking {
            val s = store(FakeVault(UnsatisfiedLinkError("no jnidispatch")))
            assertEquals(SecretResult.Failed("keychain error"), s.get("a"))
        }

    @Test
    fun aMissingBackendIsNoSystemKeychain() =
        runBlocking {
            val s = KeyringSecretStore.over({ throw NoVaultException() }, Dispatchers.IO)
            assertEquals(SecretResult.Failed("no system keychain"), s.get("a"))
            assertEquals(SecretResult.Failed("no system keychain"), s.set("a", secret))
            assertEquals(SecretResult.Failed("no system keychain"), s.delete("a"))
        }

    @Test
    fun closingTheStoreClosesTheVaultItOpened() =
        runBlocking {
            val vault = FakeVault()
            val s = store(vault)
            s.get("a")
            s.close()
            assertEquals(1, vault.closed)
        }

    @Test
    fun closingAStoreThatNeverOpenedItsVaultOpensNothing() {
        var opened = 0
        KeyringSecretStore
            .over({
                opened++
                FakeVault()
            }, Dispatchers.IO)
            .close()
        assertEquals(0, opened)
    }

    @Test
    fun theVaultIsOpenedOnceAndOffTheCallingThread() =
        runBlocking {
            var opened = 0
            var openedOn: Thread? = null
            val s =
                KeyringSecretStore.over({
                    opened++
                    openedOn = Thread.currentThread()
                    FakeVault()
                }, Dispatchers.IO)
            s.get("a")
            s.get("b")
            assertEquals(1, opened)
            assertNotEquals(Thread.currentThread(), openedOn)
        }

    // The texts below are what java-keyring 1.0.4 really throws (macOS and Linux verified by a probe, Windows from the library source).
    // KeychainSpike checks the same two on the real keychain, so a library update that rewords them is caught.
    private val macGet = "No stored credentials match anydm account: proxy:usb"
    private val macDelete = "No password to delete. No stored credentials match anydm account: proxy:usb"

    @Test
    fun absentEntriesAreRecognisedByTheLibrarysRealMessages() {
        assertTrue(looksAbsent(macGet))
        assertTrue(looksAbsent(macDelete))
        assertTrue(looksAbsent("Password not Found"))
        assertTrue(looksAbsent("Error code 1168"))
        assertTrue(looksAbsent("Error code 1168 (Element not found)"))
    }

    @Test
    fun otherLibraryFailuresAreNotAbsentEntries() {
        assertFalse(looksAbsent("User canceled the operation."))
        assertFalse(looksAbsent("Authorization/Authentication failed."))
        assertFalse(looksAbsent("Error code 11680"))
        assertFalse(looksAbsent("Error code 5"))
        assertFalse(looksAbsent("The item could not be found in the keychain."))
        assertFalse(looksAbsent(null))
    }

    @Test
    fun aMissingEntryThatTheLibraryReportsReadsAsNullAndDeletesQuietly() =
        runBlocking {
            val s = store(FakeVault(PasswordAccessException(macGet)))
            assertEquals(SecretResult.Ok(null), s.get("proxy:usb"))
            val d = store(FakeVault(PasswordAccessException(macDelete)))
            assertEquals(SecretResult.Ok(null), d.delete("proxy:usb"))
        }

    @Test
    fun aRealLibraryFailureIsStillAKeychainError() =
        runBlocking {
            val s = store(FakeVault(PasswordAccessException("User canceled the operation.")))
            assertEquals(SecretResult.Failed("keychain error"), s.get("proxy:usb"))
            assertEquals(SecretResult.Failed("keychain error"), s.delete("proxy:usb"))
            assertEquals(SecretResult.Failed("keychain error"), s.set("proxy:usb", secret))
        }

    @Test
    fun aSecretResultNeverPrintsTheSecret() {
        assertFalse(SecretResult.Ok(secret).toString().contains(secret))
        assertEquals("Ok(value=null)", SecretResult.Ok(null).toString())
    }

    @Test
    fun theUnavailableStoreFailsEveryCallWithNoSystemKeychain() =
        runBlocking {
            val expected = SecretResult.Failed("no system keychain")
            assertEquals(expected, UnavailableSecretStore.get("a"))
            assertEquals(expected, UnavailableSecretStore.set("a", secret))
            assertEquals(expected, UnavailableSecretStore.delete("a"))
        }

    @Test
    fun theInMemoryStoreKeepsASecretForTheSession() =
        runBlocking {
            val s = InMemorySecretStore()
            assertEquals(SecretResult.Ok(null), s.get("a"))
            s.set("a", secret)
            assertEquals(SecretResult.Ok(secret), s.get("a"))
            s.delete("a")
            assertEquals(SecretResult.Ok(null), s.get("a"))
        }

    @Test
    fun theAccountNameUsesTheNetworkId() {
        assertEquals("proxy:usb", proxyAccount("usb"))
        assertEquals("anydm", KEYCHAIN_SERVICE)
    }
}
