package dev.anydm.engine.ftp

import dev.anydm.engine.http.Credentials
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class KeychainCredentialsTest {
    private val keychain = FakeKeychain()
    private val credentials = KeychainCredentials(keychain)

    @Test
    fun aSavedLoginIsReadBack() =
        runTest {
            credentials.save("ftp://h:21", Credentials("ann", "pw")) shouldBe true
            KeychainCredentials(keychain).credentialsFor("ftp://h:21") shouldBe Credentials("ann", "pw")
        }

    @Test
    fun aKeyLoginIsReadBackAndKeepsItsPassphraseOutOfToString() =
        runTest {
            val login = SshKeyLogin("ann", "/home/ann/.ssh/id_ed25519", "s3cret")
            credentials.saveKey("sftp://h:22", login) shouldBe true
            val back = KeychainCredentials(keychain).keyFor("sftp://h:22")
            back shouldBe login
            back.toString() shouldNotContain "s3cret"
        }

    @Test
    fun aKeyLoginWithoutAPassphraseRoundTrips() =
        runTest {
            val login = SshKeyLogin("ann", "/k", null)
            credentials.saveKey("sftp://h:22", login)
            KeychainCredentials(keychain).keyFor("sftp://h:22") shouldBe login
        }

    @Test
    fun twoOriginsAreSeparate() =
        runTest {
            credentials.save("ftp://a:21", Credentials("u1", "p1"))
            credentials.save("ftp://b:21", Credentials("u2", "p2"))
            credentials.credentialsFor("ftp://a:21") shouldBe Credentials("u1", "p1")
            credentials.credentialsFor("ftp://b:21") shouldBe Credentials("u2", "p2")
            credentials.credentialsFor("ftp://c:21") shouldBe null
        }

    @Test
    fun aSecondReadDoesNotAskTheKeychainAgain() =
        runTest {
            credentials.save("ftp://h:21", Credentials("ann", "pw"))
            val reader = KeychainCredentials(keychain)
            reader.credentialsFor("ftp://h:21")
            val after = keychain.gets
            reader.credentialsFor("ftp://h:21")
            keychain.gets shouldBe after
        }

    @Test
    fun aFailingKeychainKeepsTheLoginInMemoryAndReturnsFalse() =
        runTest {
            keychain.failing = true
            credentials.save("ftp://h:21", Credentials("ann", "pw")) shouldBe false
            credentials.saveKey("sftp://h:22", SshKeyLogin("ann", "/k", null)) shouldBe false
            credentials.credentialsFor("ftp://h:21") shouldBe Credentials("ann", "pw")
            credentials.keyFor("sftp://h:22") shouldBe SshKeyLogin("ann", "/k", null)
            keychain.entries.isEmpty() shouldBe true
        }

    @Test
    fun aFailingKeychainReadMeansNoLoginAndTriesAgainNextTime() =
        runTest {
            credentials.save("ftp://h:21", Credentials("ann", "pw"))
            val reader = KeychainCredentials(keychain)
            keychain.failing = true
            reader.credentialsFor("ftp://h:21") shouldBe null
            keychain.failing = false
            reader.credentialsFor("ftp://h:21") shouldBe Credentials("ann", "pw")
        }

    @Test
    fun aLoginWithAColonInTheUserIsRejectedBeforeItIsStored() {
        shouldThrow<IllegalArgumentException> { Credentials("a:b", "pw") }
    }

    @Test
    fun theKeychainValueIsNotThePlainPassword() =
        runTest {
            credentials.save("ftp://h:21", Credentials("ann", "pw"))
            val stored = keychain.entries.values.single()
            stored shouldNotContain "ann:pw"
            stored.startsWith("{") shouldBe true
        }

    @Test
    fun aMalformedStoredValueReadsAsNoLogin() =
        runTest {
            keychain.entries["anydm:ftp://h:21"] = "not json"
            keychain.entries["anydm-key:sftp://h:22"] = "{\"user\":1}"
            credentials.credentialsFor("ftp://h:21") shouldBe null
            credentials.keyFor("sftp://h:22") shouldBe null
        }
}
