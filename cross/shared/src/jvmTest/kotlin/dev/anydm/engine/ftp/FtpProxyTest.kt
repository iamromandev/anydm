package dev.anydm.engine.ftp

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.http.Credentials
import dev.anydm.engine.net.FakeProxyServer
import dev.anydm.engine.net.ProxyEndpoint
import dev.anydm.engine.net.ProxyRoute
import dev.anydm.engine.net.ProxyRouter
import dev.anydm.engine.net.ProxyType
import dev.anydm.engine.net.withProxy
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** FTP, FTPS and SFTP through a proxy (NET-15): only the proxy looks the name up, and a refusal is a proxy failure. */
class FtpProxyTest {
    private val opened = mutableListOf<AutoCloseable>()
    private val data = ByteArray(120_000) { (it % 251).toByte() }
    private val ann = RemoteLogin.Password(Credentials("ann", "pw"))
    private val tlsDir = Files.createTempDirectory("anydm-tls").toFile()
    private val keyDir = Files.createTempDirectory("anydm-keys").toFile()
    private val name = "files.unresolvable.test"

    @AfterTest
    fun close() {
        opened.forEach { runCatching { it.close() } }
        tlsDir.deleteRecursively()
        keyDir.deleteRecursively()
    }

    private fun <T : AutoCloseable> T.opened(): T = also { opened += it }

    private fun router(route: ProxyRoute) = ProxyRouter { _, _ -> route }

    private fun socks(
        target: Int,
        login: Pair<String, String>? = null,
    ) = FakeProxyServer(FakeProxyServer.Kind.SOCKS5, login, InetSocketAddress("127.0.0.1", target)).opened()

    private fun via(
        proxy: FakeProxyServer,
        type: ProxyType = ProxyType.SOCKS5,
        login: Pair<String, String>? = null,
    ) = ProxyRoute.Via(ProxyEndpoint(type, "127.0.0.1", proxy.port, login?.first, login?.second))

    private suspend fun RemoteClient.bytes(path: String = "/a.bin"): ByteArray {
        val out = ByteArrayOutputStream()
        read(path, 0, null) { buffer, n -> out.write(buffer, 0, n) }
        return out.toByteArray()
    }

    private fun <T> proxied(
        route: ProxyRoute,
        block: suspend () -> T,
    ): T = runBlocking { withProxy(router(route), DownloadId(1)) { block() } }

    @Test
    fun ftpThroughSocks5SendsTheNameAndTheDataConnectionGoesThroughToo() {
        val ftp = FtpServerFixture().opened().also { it.put("/a.bin", data) }
        val proxy = socks(ftp.port)
        val client = CommonsNetClient()

        val got =
            proxied(via(proxy)) {
                client.connect(RemoteTarget(FtpScheme.FTP, name, ftp.port, "/a.bin"), ann)
                client.bytes().also { client.close() }
            }

        got.contentEquals(data) shouldBe true
        proxy.requests.toList() shouldContain "$name:${ftp.port}"
        proxy.requests.size shouldBe 2 // the control connection and the one data connection
    }

    @Test
    fun ftpThroughAnHttpProxyWithALogin() {
        val ftp = FtpServerFixture().opened().also { it.put("/a.bin", data) }
        val proxy = FakeProxyServer(FakeProxyServer.Kind.HTTP, "bob" to "pw", InetSocketAddress("127.0.0.1", ftp.port)).opened()
        val client = CommonsNetClient()

        val got =
            proxied(via(proxy, ProxyType.HTTP, "bob" to "pw")) {
                client.connect(RemoteTarget(FtpScheme.FTP, name, ftp.port, "/a.bin"), ann)
                client.bytes().also { client.close() }
            }

        got.contentEquals(data) shouldBe true
        proxy.logins.toList().distinct() shouldBe listOf("bob:pw")
    }

    @Test
    fun implicitFtpsThroughSocks5ChecksTheCertificateAgainstTheName() {
        val tls = TlsFixture.create(tlsDir, name, withIp = false)
        val ftp = FtpServerFixture(tls = tls, implicit = true).opened().also { it.put("/a.bin", data) }
        val proxy = socks(ftp.port)
        val client = CommonsNetClient(TrustConfig.fromKeystore(tls.keystore.path, tls.password))

        val got =
            proxied(via(proxy)) {
                client.connect(RemoteTarget(FtpScheme.FTPS, name, ftp.port, "/a.bin"), ann)
                client.bytes().also { client.close() }
            }

        got.contentEquals(data) shouldBe true
        proxy.requests.toList() shouldContain "$name:${ftp.port}"
        // The control connection and the one data connection: the file's bytes went through the proxy too.
        proxy.requests.size shouldBe 2
    }

    @Test
    fun explicitFtpsThroughSocks5SendsItsDataThroughTheProxyToo() {
        val tls = TlsFixture.create(tlsDir, name, withIp = false)
        val ftp = FtpServerFixture(tls = tls).opened().also { it.put("/a.bin", data) }
        val proxy = socks(ftp.port)
        val client = CommonsNetClient(TrustConfig.fromKeystore(tls.keystore.path, tls.password))

        val got =
            proxied(via(proxy)) {
                client.connect(RemoteTarget(FtpScheme.FTPES, name, ftp.port, "/a.bin"), ann)
                client.bytes().also { client.close() }
            }

        got.contentEquals(data) shouldBe true
        proxy.requests.size shouldBe 2
    }

    @Test
    fun sftpThroughSocks5KeepsTheNameForTheHostKey() {
        val sftp = SftpServerFixture().opened().also { it.put("/a.bin", data) }
        val proxy = socks(sftp.port)
        val hostKeys = FakeHostKeyStore()
        val client = SshjClient(hostKeys)

        val got =
            proxied(via(proxy)) {
                client.connect(RemoteTarget(FtpScheme.SFTP, name, sftp.port, "/a.bin"), ann)
                client.bytes().also { client.close() }
            }

        got.contentEquals(data) shouldBe true
        proxy.requests.toList() shouldBe listOf("$name:${sftp.port}")
        hostKeys.keys.keys.single() shouldBe (name to sftp.port)
    }

    @Test
    fun aBlockedRouteConnectsToNothingForEitherClient() {
        val ftp = FtpServerFixture().opened()
        val sftp = SftpServerFixture().opened()
        val blocked = ProxyRoute.Blocked("no password saved")

        for (target in listOf(
            RemoteTarget(FtpScheme.FTP, "127.0.0.1", ftp.port, "/a"),
            RemoteTarget(FtpScheme.SFTP, "127.0.0.1", sftp.port, "/a"),
        )) {
            val client: RemoteClient = if (target.scheme == FtpScheme.SFTP) SshjClient(FakeHostKeyStore()) else CommonsNetClient()
            val failure = assertFailsWith<RemoteException> { proxied(blocked) { client.connect(target, ann) } }
            failure.failure shouldBe RemoteFailure.PROXY
        }
    }

    @Test
    fun aProxyThatRefusesTheLoginIsAProxyFailureNotATransientOne() {
        val ftp = FtpServerFixture().opened()
        val sftp = SftpServerFixture().opened()
        val ftpProxy = socks(ftp.port, "ann" to "pw")
        val sftpProxy = socks(sftp.port, "ann" to "pw")

        val ftpFailure =
            assertFailsWith<RemoteException> {
                proxied(
                    via(ftpProxy, login = "ann" to "bad"),
                ) { CommonsNetClient().connect(RemoteTarget(FtpScheme.FTP, name, ftp.port, "/a"), ann) }
            }
        val sftpFailure =
            assertFailsWith<RemoteException> {
                proxied(via(sftpProxy, login = "ann" to "bad")) {
                    SshjClient(FakeHostKeyStore()).connect(RemoteTarget(FtpScheme.SFTP, name, sftp.port, "/a"), ann)
                }
            }

        ftpFailure.failure shouldBe RemoteFailure.PROXY
        sftpFailure.failure shouldBe RemoteFailure.PROXY
        RemoteException(RemoteFailure.PROXY, "x").asFailed().errorClass shouldBe dev.anydm.engine.core.ErrorClass.PROXY
    }

    @Test
    fun aDirectRouteNeverAsksTheJvmsOwnProxySelectorForEitherClient() {
        val ftp = FtpServerFixture().opened().also { it.put("/a.bin", data) }
        val sftp = SftpServerFixture().opened().also { it.put("/a.bin", data) }
        val asked = mutableListOf<String>()
        val saved = java.net.ProxySelector.getDefault()
        java.net.ProxySelector.setDefault(
            object : java.net.ProxySelector() {
                override fun select(uri: java.net.URI): List<java.net.Proxy> {
                    // Only our clients count: other threads of the test JVM (fake servers) connect too.
                    if (Throwable().stackTrace.any { it.className.startsWith("dev.anydm.engine.ftp.") && "Test" !in it.className }) {
                        asked +=
                            uri.toString()
                    }
                    return listOf(java.net.Proxy(java.net.Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", 9)))
                }

                override fun connectFailed(
                    uri: java.net.URI?,
                    sa: java.net.SocketAddress?,
                    ioe: java.io.IOException?,
                ) = Unit
            },
        )
        try {
            val ftpBytes =
                proxied(ProxyRoute.Direct) {
                    val client = CommonsNetClient()
                    client.connect(RemoteTarget(FtpScheme.FTP, "127.0.0.1", ftp.port, "/a.bin"), ann)
                    client.bytes().also { client.close() }
                }
            val sftpBytes =
                proxied(ProxyRoute.Direct) {
                    val client = SshjClient(FakeHostKeyStore())
                    client.connect(RemoteTarget(FtpScheme.SFTP, "127.0.0.1", sftp.port, "/a.bin"), ann)
                    client.bytes().also { client.close() }
                }

            val tls = TlsFixture.create(tlsDir, "localhost")
            val trust = TrustConfig.fromKeystore(tls.keystore.path, tls.password)
            val implicit = FtpServerFixture(tls = tls, implicit = true).opened().also { it.put("/a.bin", data) }
            val explicit = FtpServerFixture(tls = tls).opened().also { it.put("/a.bin", data) }
            val secured =
                listOf(FtpScheme.FTPS to implicit.port, FtpScheme.FTPES to explicit.port).map { (scheme, port) ->
                    proxied(ProxyRoute.Direct) {
                        val client = CommonsNetClient(trust)
                        client.connect(RemoteTarget(scheme, "localhost", port, "/a.bin"), ann)
                        client.bytes().also { client.close() }
                    }
                }

            ftpBytes.contentEquals(data) shouldBe true
            sftpBytes.contentEquals(data) shouldBe true
            secured.all { it.contentEquals(data) } shouldBe true
            asked shouldBe emptyList()
        } finally {
            java.net.ProxySelector.setDefault(saved)
        }
    }
}
