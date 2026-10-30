package dev.anydm.engine.store

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** Trusted SSH host keys (FTP-04), on a new database from [newDriver] for each test. */
abstract class HostKeyStoreContract : StoreTestBase() {
    private val key = HostKey("ssh-ed25519", "SHA256:abc")

    @Test
    fun aKeyRoundTrips() =
        runTest {
            val store = newStore()

            store.hostKeys.get("files.example.com", 22) shouldBe null
            store.hostKeys.put("files.example.com", 22, key)

            store.hostKeys.get("files.example.com", 22) shouldBe key
        }

    @Test
    fun hostsAreCaseInsensitive() =
        runTest {
            val store = newStore()
            store.hostKeys.put("Files.Example.COM", 22, key)

            store.hostKeys.get("files.example.com", 22) shouldBe key
            store.hostKeys.forget("FILES.example.com", 22) shouldBe true
        }

    @Test
    fun thePortIsPartOfTheKey() =
        runTest {
            val store = newStore()
            store.hostKeys.put("h", 22, key)

            store.hostKeys.get("h", 2222) shouldBe null
        }

    @Test
    fun putReplacesAKey() =
        runTest {
            val store = newStore()
            store.hostKeys.put("h", 22, key)
            val other = HostKey("ssh-rsa", "SHA256:xyz")

            store.hostKeys.put("h", 22, other)

            store.hostKeys.get("h", 22) shouldBe other
        }

    @Test
    fun forgetRemovesAndReportsWhetherItDid() =
        runTest {
            val store = newStore()
            store.hostKeys.put("h", 22, key)

            store.hostKeys.forget("h", 22) shouldBe true
            store.hostKeys.get("h", 22) shouldBe null
            store.hostKeys.forget("h", 22) shouldBe false
        }
}
