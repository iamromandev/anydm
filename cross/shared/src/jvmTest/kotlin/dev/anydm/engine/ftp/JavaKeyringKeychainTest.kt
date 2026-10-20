package dev.anydm.engine.ftp

import com.github.javakeyring.BackendNotSupportedException
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The java-keyring wrapper (FTP-06). The round trip talks to the machine's real keychain, so it runs only when
 * `ANYDM_REAL_KEYCHAIN=1` is set: it saves one made-up item, reads it, and deletes it. Otherwise only the no-keychain
 * path runs.
 */
class JavaKeyringKeychainTest {
    private fun blocking(block: suspend () -> Unit) = runBlocking { block() }

    private val real = System.getenv("ANYDM_REAL_KEYCHAIN") == "1"

    @Test
    fun noBackendMeansKeychainUnavailable() =
        blocking {
            val keychain = JavaKeyringKeychain("anydm-test") { throw BackendNotSupportedException("no keychain here") }

            assertFailsWith<KeychainUnavailable> { keychain.get("a") }
            assertFailsWith<KeychainUnavailable> { keychain.put("a", "b") }
            assertFailsWith<KeychainUnavailable> { keychain.remove("a") }
        }

    @Test
    fun aNativeLibraryThatWillNotLoadIsKeychainUnavailable() =
        blocking {
            val keychain = JavaKeyringKeychain("anydm-test") { throw UnsatisfiedLinkError("osxkeychain.so: not a valid code signature") }

            assertFailsWith<KeychainUnavailable> { keychain.get("a") }
            assertFailsWith<KeychainUnavailable> { keychain.put("a", "b") }
        }

    @Test
    fun anUnexpectedErrorFromTheBackendIsKeychainUnavailable() =
        blocking {
            val keychain = JavaKeyringKeychain("anydm-test") { throw IllegalStateException("D-Bus went away") }

            assertFailsWith<KeychainUnavailable> { keychain.get("a") }
        }

    @Test
    fun aMissingBackendIsLookedForAgainOnTheNextCall() =
        blocking {
            var tries = 0
            val keychain =
                JavaKeyringKeychain("anydm-test") {
                    tries++
                    throw BackendNotSupportedException("not yet")
                }

            assertFailsWith<KeychainUnavailable> { keychain.get("a") }
            assertFailsWith<KeychainUnavailable> { keychain.get("a") }

            tries shouldBe 2
        }

    @Test
    fun aSecretRoundTripsThroughTheRealKeychain() =
        blocking {
            assumeTrue("set ANYDM_REAL_KEYCHAIN=1 to use the real keychain", real)
            val keychain = JavaKeyringKeychain("anydm-test")
            val account = "anydm-test-${System.nanoTime()}"
            try {
                keychain.put(account, "s3cret")

                keychain.get(account) shouldBe "s3cret"
            } finally {
                runCatching { keychain.remove(account) }
            }
        }

    @Test
    fun aMissingAccountIsNull() =
        blocking {
            assumeTrue("set ANYDM_REAL_KEYCHAIN=1 to use the real keychain", real)

            JavaKeyringKeychain("anydm-test").get("anydm-test-missing-${System.nanoTime()}") shouldBe null
        }

    @Test
    fun removeDeletesIt() =
        blocking {
            assumeTrue("set ANYDM_REAL_KEYCHAIN=1 to use the real keychain", real)
            val keychain = JavaKeyringKeychain("anydm-test")
            val account = "anydm-test-${System.nanoTime()}"
            keychain.put(account, "gone soon")

            keychain.remove(account)

            keychain.get(account) shouldBe null
            keychain.remove(account)
        }
}
