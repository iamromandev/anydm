package dev.anydm.engine.ftp

import org.apache.ftpserver.ConnectionConfigFactory
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.ftplet.Authority
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.ssl.SslConfigurationFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** A self-signed certificate in a JKS keystore, made with the JDK's `keytool`: [dns] is the one name it is good for. */
internal class TlsFixture(
    val keystore: File,
    val password: String,
) {
    companion object {
        fun create(
            dir: File,
            dns: String,
            withIp: Boolean = true,
        ): TlsFixture {
            val store = File(dir, "$dns.jks")
            val keytool = File(System.getProperty("java.home"), "bin/keytool").path
            val names = if (withIp) "dns:$dns,ip:127.0.0.1" else "dns:$dns"
            val process =
                ProcessBuilder(
                    keytool,
                    "-genkeypair",
                    "-alias",
                    "server",
                    "-keyalg",
                    "RSA",
                    "-keysize",
                    "2048",
                    "-validity",
                    "30",
                    "-dname",
                    "CN=$dns",
                    "-ext",
                    "san=$names",
                    "-keystore",
                    store.path,
                    "-storepass",
                    "changeit",
                    "-keypass",
                    "changeit",
                    "-storetype",
                    "JKS",
                ).redirectErrorStream(true).start()
            val output = process.inputStream.readBytes().decodeToString()
            check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "keytool failed: $output" }
            return TlsFixture(store, "changeit")
        }
    }
}

/**
 * An Apache FtpServer on loopback with a home folder of files: user `ann` / `pw`, and `anonymous`. [tls] turns on TLS,
 * implicit or explicit; [maxLogins] limits the logins at once (0 for no limit).
 */
internal class FtpServerFixture(
    val home: File = Files.createTempDirectory("anydm-ftp").toFile(),
    tls: TlsFixture? = null,
    implicit: Boolean = false,
    maxLogins: Int = 0,
) : AutoCloseable {
    private val server: FtpServer
    val port: Int

    init {
        val factory = FtpServerFactory()
        val listener =
            ListenerFactory().apply {
                this.port = 0
                serverAddress = "127.0.0.1"
                if (tls != null) {
                    sslConfiguration =
                        SslConfigurationFactory()
                            .apply {
                                keystoreFile = tls.keystore
                                keystorePassword = tls.password
                                keyPassword = tls.password
                            }.createSslConfiguration()
                    isImplicitSsl = implicit
                }
            }
        factory.addListener("default", listener.createListener())
        factory.connectionConfig =
            ConnectionConfigFactory()
                .apply {
                    isAnonymousLoginEnabled = true
                    this.maxLogins = if (maxLogins > 0) maxLogins else 10_000
                    maxAnonymousLogins = if (maxLogins > 0) maxLogins else 10_000
                }.createConnectionConfig()
        for ((name, password) in listOf("ann" to "pw", "anonymous" to "")) {
            factory.userManager.save(
                BaseUser().apply {
                    this.name = name
                    this.password = password
                    homeDirectory = home.path
                    authorities = listOf<Authority>(WritePermission())
                },
            )
        }
        server = factory.createServer()
        server.start()
        port = factory.listeners.getValue("default").port
    }

    fun put(
        path: String,
        bytes: ByteArray,
    ) {
        val file = File(home, path.removePrefix("/"))
        file.parentFile.mkdirs()
        file.writeBytes(bytes)
    }

    override fun close() {
        server.stop()
        home.deleteRecursively()
    }
}
