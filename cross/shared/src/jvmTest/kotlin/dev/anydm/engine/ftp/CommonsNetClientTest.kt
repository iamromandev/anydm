package dev.anydm.engine.ftp

import dev.anydm.engine.http.Credentials
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** The Commons Net client (FTP-01 to FTP-03) against a real Apache FtpServer and, for what it won't do, a scripted one. */
class CommonsNetClientTest {
    private val opened = mutableListOf<AutoCloseable>()
    private val clients = mutableListOf<CommonsNetClient>()
    private val data = ByteArray(200_000) { (it % 251).toByte() }
    private val ann = RemoteLogin.Password(Credentials("ann", "pw"))
    private val tlsDir = Files.createTempDirectory("anydm-tls").toFile()

    // A test method returns nothing: runBlocking would return its last expression.
    private fun blocking(block: suspend () -> Unit) = runBlocking { block() }

    @AfterTest
    fun close() {
        runBlocking { clients.forEach { runCatching { it.close() } } }
        opened.forEach { runCatching { it.close() } }
        tlsDir.deleteRecursively()
    }

    private fun server(
        tls: TlsFixture? = null,
        implicit: Boolean = false,
        maxLogins: Int = 0,
    ) = FtpServerFixture(tls = tls, implicit = implicit, maxLogins = maxLogins).also {
        opened += it
        it.put("/a.bin", data)
    }

    private fun stub(
        restReply: String = "350 Restarting",
        sizeReply: String? = null,
        hangUp: Boolean = false,
    ) = StubFtpServer(data, restReply, sizeReply, hangUp).also { opened += it }

    private fun client(trust: TrustConfig = TrustConfig.Jvm) = CommonsNetClient(trust).also { clients += it }

    private fun target(
        scheme: FtpScheme,
        port: Int,
        path: String = "/a.bin",
    ) = RemoteTarget(scheme, "127.0.0.1", port, path)

    private suspend fun CommonsNetClient.bytes(
        path: String,
        offset: Long = 0,
        length: Long? = null,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        read(path, offset, length) { buffer, n -> out.write(buffer, 0, n) }
        return out.toByteArray()
    }

    private fun trusting(fixture: TlsFixture) = TrustConfig.fromKeystore(fixture.keystore.path, fixture.password)

    private suspend fun failure(block: suspend () -> Unit): RemoteFailure = assertFailsWith<RemoteException> { block() }.failure

    @Test
    fun readsAFileFromAnOffsetWithRest() =
        blocking {
            val ftp = server()
            val client = client()
            client.connect(target(FtpScheme.FTP, ftp.port), ann)

            client.bytes("/a.bin", offset = 50_000).contentEquals(data.copyOfRange(50_000, data.size)) shouldBe true
        }

    @Test
    fun readsABoundedRangeAndStopsAtItsLength() =
        blocking {
            val ftp = server()
            val first = client()
            first.connect(target(FtpScheme.FTP, ftp.port), ann)

            first.bytes("/a.bin", offset = 1000, length = 5000).contentEquals(data.copyOfRange(1000, 6000)) shouldBe true

            val second = client()
            second.connect(target(FtpScheme.FTP, ftp.port), ann)
            second.bytes("/a.bin", offset = 190_000, length = 100_000).contentEquals(data.copyOfRange(190_000, data.size)) shouldBe true
        }

    @Test
    fun readsTheWholeFileWithNoLength() =
        blocking {
            val ftp = server()
            val client = client()
            client.connect(target(FtpScheme.FTP, ftp.port), ann)

            client.bytes("/a.bin").contentEquals(data) shouldBe true
        }

    @Test
    fun statGivesSizeAndModifiedWhenTheServerHasThem() =
        blocking {
            val ftp = server()
            val client = client()
            client.connect(target(FtpScheme.FTP, ftp.port), ann)

            val stat = client.stat("/a.bin")

            stat.size shouldBe data.size.toLong()
            (stat.modified!! > 1_600_000_000L) shouldBe true
        }

    @Test
    fun anonymousLoginWorks() =
        blocking {
            val ftp = server()
            val client = client()
            client.connect(target(FtpScheme.FTP, ftp.port), RemoteLogin.Anonymous)

            client.stat("/a.bin").size shouldBe data.size.toLong()
        }

    @Test
    fun aWrongPasswordIsAuth() =
        blocking {
            val ftp = server()

            failure { client().connect(target(FtpScheme.FTP, ftp.port), RemoteLogin.Password(Credentials("ann", "wrong"))) } shouldBe
                RemoteFailure.AUTH
        }

    @Test
    fun aKeyLoginIsAuthForFtp() =
        blocking {
            val ftp = server()

            failure { client().connect(target(FtpScheme.FTP, ftp.port), RemoteLogin.Key(SshKeyLogin("ann", "/k", null))) } shouldBe
                RemoteFailure.AUTH
        }

    @Test
    fun aMissingFileIsGone() =
        blocking {
            val ftp = server()
            val client = client()
            client.connect(target(FtpScheme.FTP, ftp.port), ann)

            failure { client.stat("/none") } shouldBe RemoteFailure.GONE
            failure { client.bytes("/none") } shouldBe RemoteFailure.GONE
        }

    @Test
    fun aRefusedExtraConnectionIsTooManyConnections() =
        blocking {
            val ftp = server(maxLogins = 2)
            client().connect(target(FtpScheme.FTP, ftp.port), ann)
            client().connect(target(FtpScheme.FTP, ftp.port), ann)

            failure { client().connect(target(FtpScheme.FTP, ftp.port), ann) } shouldBe RemoteFailure.TOO_MANY_CONNECTIONS
        }

    @Test
    fun explicitTlsWorksWithATrustedCertificate() =
        blocking {
            val tls = TlsFixture.create(tlsDir, "localhost")
            val ftp = server(tls = tls)
            val client = client(trusting(tls))
            client.connect(target(FtpScheme.FTPES, ftp.port), ann)

            client.bytes("/a.bin", offset = 100).contentEquals(data.copyOfRange(100, data.size)) shouldBe true
        }

    @Test
    fun implicitTlsWorksWithATrustedCertificate() =
        blocking {
            val tls = TlsFixture.create(tlsDir, "localhost")
            val ftp = server(tls = tls, implicit = true)
            val client = client(trusting(tls))
            client.connect(target(FtpScheme.FTPS, ftp.port), ann)

            client.bytes("/a.bin").contentEquals(data) shouldBe true
        }

    @Test
    fun anUntrustedCertificateIsACertificateFailure() =
        blocking {
            val tls = TlsFixture.create(tlsDir, "localhost")
            val ftp = server(tls = tls)

            failure { client().connect(target(FtpScheme.FTPES, ftp.port), ann) } shouldBe RemoteFailure.CERTIFICATE
        }

    @Test
    fun aHostnameMismatchIsACertificateFailure() =
        blocking {
            val tls = TlsFixture.create(tlsDir, "other.example", withIp = false)
            val ftp = server(tls = tls)

            failure { client(trusting(tls)).connect(target(FtpScheme.FTPES, ftp.port), ann) } shouldBe RemoteFailure.CERTIFICATE
        }

    @Test
    fun aServerThatRejectsRestIsRestUnsupported() =
        blocking {
            val stub = stub(restReply = "502 REST not implemented")
            val client = client()
            client.connect(target(FtpScheme.FTP, stub.port), ann)

            failure { client.bytes("/a.bin", offset = 10) } shouldBe RemoteFailure.REST_UNSUPPORTED
        }

    @Test
    fun aSpaceAndUnicodeInThePathWork() =
        blocking {
            val ftp = server()
            ftp.put("/my dir/é file.txt", data)
            val client = client()
            client.connect(target(FtpScheme.FTP, ftp.port), ann)

            client.stat("/my dir/é file.txt").size shouldBe data.size.toLong()
            client.bytes("/my dir/é file.txt", length = 10).contentEquals(data.copyOfRange(0, 10)) shouldBe true
        }

    @Test
    fun aFileWithoutSizeSupportStillReads() =
        blocking {
            val stub = stub(sizeReply = "502 SIZE not implemented")
            val client = client()
            client.connect(target(FtpScheme.FTP, stub.port), ann)

            client.stat("/a.bin") shouldBe RemoteStat(null, null)
            client.bytes("/a.bin").contentEquals(data) shouldBe true
        }

    @Test
    fun aDroppedControlConnectionIsTransient() =
        blocking {
            val stub = stub(hangUp = true)

            failure { client().connect(target(FtpScheme.FTP, stub.port), ann) } shouldBe RemoteFailure.TRANSIENT
        }

    @Test
    fun nothingListeningIsTransient() =
        blocking {
            val closed = java.net.ServerSocket(0).use { it.localPort }

            failure { client().connect(target(FtpScheme.FTP, closed), ann) } shouldBe RemoteFailure.TRANSIENT
        }
}
