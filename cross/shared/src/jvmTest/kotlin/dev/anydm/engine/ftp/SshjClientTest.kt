package dev.anydm.engine.ftp

import dev.anydm.engine.http.Credentials
import dev.anydm.engine.store.HostKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** The sshj client (FTP-04, FTP-05) against a real Apache MINA SSHD. */
class SshjClientTest {
    private val opened = mutableListOf<AutoCloseable>()
    private val clients = mutableListOf<SshjClient>()
    private val hostKeys = FakeHostKeyStore()
    private val data = ByteArray(300_000) { (it % 251).toByte() }
    private val password = RemoteLogin.Password(Credentials("ann", "pw"))
    private val keyDir = Files.createTempDirectory("anydm-keys").toFile()

    // A test method returns nothing: runBlocking would return its last expression.
    private fun blocking(block: suspend CoroutineScope.() -> Unit) = runBlocking { block() }

    @AfterTest
    fun close() {
        runBlocking { clients.forEach { runCatching { it.close() } } }
        opened.forEach { runCatching { it.close() } }
        keyDir.deleteRecursively()
    }

    private fun server(authorized: List<ClientKey> = emptyList()) =
        SftpServerFixture(authorized = authorized).also {
            opened += it
            it.put("/a.bin", data)
        }

    private fun client() = SshjClient(hostKeys).also { clients += it }

    private fun target(
        port: Int,
        path: String = "/a.bin",
    ) = RemoteTarget(FtpScheme.SFTP, "127.0.0.1", port, path)

    private suspend fun SshjClient.bytes(
        path: String,
        offset: Long = 0,
        length: Long? = null,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        read(path, offset, length) { buffer, n -> out.write(buffer, 0, n) }
        return out.toByteArray()
    }

    private suspend fun failure(block: suspend () -> Unit): RemoteException = assertFailsWith<RemoteException> { block() }

    @Test
    fun passwordLoginReadsAFile() =
        blocking {
            val sftp = server()
            val client = client()
            client.connect(target(sftp.port), password)

            client.bytes("/a.bin").contentEquals(data) shouldBe true
        }

    @Test
    fun readsFromAnOffsetAndForALength() =
        blocking {
            val sftp = server()
            val client = client()
            client.connect(target(sftp.port), password)

            client.bytes("/a.bin", offset = 70_000).contentEquals(data.copyOfRange(70_000, data.size)) shouldBe true
            client.bytes("/a.bin", offset = 1_000, length = 5_000).contentEquals(data.copyOfRange(1_000, 6_000)) shouldBe true
        }

    @Test
    fun parallelChannelsReadDifferentRanges() =
        blocking {
            val sftp = server()
            val quarter = data.size / 4
            val parts =
                (0 until 4)
                    .map { i ->
                        async(Dispatchers.IO) {
                            val client = client()
                            client.connect(target(sftp.port), password)
                            val end = if (i == 3) data.size else (i + 1) * quarter
                            client.bytes("/a.bin", offset = i * quarter.toLong(), length = (end - i * quarter).toLong())
                        }
                    }.awaitAll()

            parts.reduce { a, b -> a + b }.contentEquals(data) shouldBe true
        }

    @Test
    fun aKeyLoginWorks() =
        blocking {
            val key = ClientKey.create(keyDir)
            val sftp = server(authorized = listOf(key))
            val client = client()
            client.connect(target(sftp.port), RemoteLogin.Key(SshKeyLogin("ann", key.privateKey.path, null)))

            client.stat("/a.bin").size shouldBe data.size.toLong()
        }

    @Test
    fun aPassphraseKeyLoginWorksAndAWrongPassphraseIsAuth() =
        blocking {
            val key = ClientKey.create(keyDir, passphrase = "open sesame")
            val sftp = server(authorized = listOf(key))

            val good = client()
            good.connect(target(sftp.port), RemoteLogin.Key(SshKeyLogin("ann", key.privateKey.path, "open sesame")))
            good.stat("/a.bin").size shouldBe data.size.toLong()

            failure {
                client().connect(target(sftp.port), RemoteLogin.Key(SshKeyLogin("ann", key.privateKey.path, "wrong")))
            }.failure shouldBe RemoteFailure.AUTH
        }

    // What `ssh-keygen` writes is the key format people really have; skipped on a machine without it.
    private fun openSshKeyWorks(
        type: String,
        passphrase: String? = null,
    ) = blocking {
        val key = ClientKey.openSsh(keyDir, type, passphrase)
        assumeTrue("no ssh-keygen here", key != null)
        val sftp = server(authorized = listOf(key!!))
        val client = client()
        client.connect(target(sftp.port), RemoteLogin.Key(SshKeyLogin("ann", key.privateKey.path, passphrase)))

        client.stat("/a.bin").size shouldBe data.size.toLong()
    }

    @Test
    fun anOpenSshEd25519KeyWorks() = openSshKeyWorks("ed25519")

    @Test
    fun anOpenSshEcdsaKeyWorks() = openSshKeyWorks("ecdsa")

    @Test
    fun anOpenSshRsaKeyWorks() = openSshKeyWorks("rsa")

    @Test
    fun anEncryptedOpenSshEd25519KeyWorks() = openSshKeyWorks("ed25519", passphrase = "open sesame")

    @Test
    fun aKeyTheServerDoesNotKnowIsAuth() =
        blocking {
            val known = ClientKey.create(keyDir)
            val stranger = ClientKey.create(keyDir)
            val sftp = server(authorized = listOf(known))

            failure {
                client().connect(target(sftp.port), RemoteLogin.Key(SshKeyLogin("ann", stranger.privateKey.path, null)))
            }.failure shouldBe RemoteFailure.AUTH
        }

    @Test
    fun aWrongPasswordIsAuth() =
        blocking {
            val sftp = server()

            failure { client().connect(target(sftp.port), RemoteLogin.Password(Credentials("ann", "wrong"))) }.failure shouldBe
                RemoteFailure.AUTH
        }

    @Test
    fun anonymousIsAuth() =
        blocking {
            val sftp = server()

            failure { client().connect(target(sftp.port), RemoteLogin.Anonymous) }.failure shouldBe RemoteFailure.AUTH
        }

    @Test
    fun aMissingFileIsGone() =
        blocking {
            val sftp = server()
            val client = client()
            client.connect(target(sftp.port), password)

            failure { client.stat("/none") }.failure shouldBe RemoteFailure.GONE
            failure { client.bytes("/none") }.failure shouldBe RemoteFailure.GONE
        }

    @Test
    fun statGivesSizeAndModified() =
        blocking {
            val sftp = server()
            val client = client()
            client.connect(target(sftp.port), password)

            val stat = client.stat("/a.bin")

            stat.size shouldBe data.size.toLong()
            (stat.modified!! > 1_600_000_000L) shouldBe true
        }

    @Test
    fun firstConnectionSavesTheFingerprint() =
        blocking {
            val sftp = server()
            client().connect(target(sftp.port), password)

            val saved = hostKeys.keys.getValue("127.0.0.1" to sftp.port)
            saved.fingerprint.startsWith("SHA256:") shouldBe true
            saved.keyType.startsWith("ecdsa") shouldBe true
        }

    @Test
    fun aKnownMatchingKeyConnects() =
        blocking {
            val sftp = server()
            client().connect(target(sftp.port), password)
            val saved = hostKeys.keys.getValue("127.0.0.1" to sftp.port)

            client().connect(target(sftp.port), password)

            hostKeys.keys.getValue("127.0.0.1" to sftp.port) shouldBe saved
        }

    @Test
    fun aChangedKeyIsHostKeyChangedWithBothFingerprintsInTheMessage() =
        blocking {
            val sftp = server()
            val other = HostKey("ecdsa-sha2-nistp256", "SHA256:someOtherServersKeyFingerprint")
            hostKeys.put("127.0.0.1", sftp.port, other)

            val error = failure { client().connect(target(sftp.port), password) }

            error.failure shouldBe RemoteFailure.HOST_KEY_CHANGED
            error.message!! shouldContain "127.0.0.1"
            error.message!! shouldContain other.fingerprint
            error.message!! shouldContain "SHA256:"
            error.message!! shouldNotContain "pw\""
        }

    @Test
    fun aChangedKeyDoesNotOverwriteTheStoredKey() =
        blocking {
            val sftp = server()
            val other = HostKey("ecdsa-sha2-nistp256", "SHA256:someOtherServersKeyFingerprint")
            hostKeys.put("127.0.0.1", sftp.port, other)

            failure { client().connect(target(sftp.port), password) }

            hostKeys.keys.getValue("127.0.0.1" to sftp.port) shouldBe other
        }

    @Test
    fun forgettingTheKeyAllowsTheNewOne() =
        blocking {
            val sftp = server()
            hostKeys.put("127.0.0.1", sftp.port, HostKey("ecdsa-sha2-nistp256", "SHA256:old"))
            failure { client().connect(target(sftp.port), password) }

            hostKeys.forget("127.0.0.1", sftp.port)
            client().connect(target(sftp.port), password)

            hostKeys.keys.getValue("127.0.0.1" to sftp.port).fingerprint shouldNotContain "old"
        }

    @Test
    fun theMessageOfAFailedLoginHasNoPassword() =
        blocking {
            val sftp = server()

            val error = failure { client().connect(target(sftp.port), RemoteLogin.Password(Credentials("ann", "hunter2"))) }

            error.message!! shouldNotContain "hunter2"
        }

    @Test
    fun nothingListeningIsTransient() =
        blocking {
            val closed = java.net.ServerSocket(0).use { it.localPort }

            failure { client().connect(target(closed), password) }.failure shouldBe RemoteFailure.TRANSIENT
        }
}
