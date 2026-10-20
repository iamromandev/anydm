package dev.anydm.engine.ftp

import org.apache.sshd.common.config.keys.AuthorizedKeyEntry
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.bouncycastle.openssl.PKCS8Generator
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8EncryptorBuilder
import java.io.File
import java.io.StringWriter
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.PublicKey

/** A client key pair: [privateKey] is a PEM file, encrypted when [passphrase] is set; the server accepts [pair]'s public key. */
internal class ClientKey(
    val publicKey: PublicKey,
    val privateKey: File,
    val passphrase: String?,
) {
    companion object {
        fun create(
            dir: File,
            passphrase: String? = null,
        ): ClientKey {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val text = StringWriter()
            // PKCS#8 ("PRIVATE KEY", or "ENCRYPTED PRIVATE KEY" with a passphrase): the form sshj reads for every key type.
            val encryptor =
                passphrase?.let {
                    JceOpenSSLPKCS8EncryptorBuilder(PKCS8Generator.AES_256_CBC).setPasssword(it.toCharArray()).build()
                }
            JcaPEMWriter(text).use { it.writeObject(JcaPKCS8Generator(pair.private, encryptor)) }
            val file = File.createTempFile("anydm-key", ".pem", dir)
            file.writeText(text.toString())
            return ClientKey(pair.public, file, passphrase)
        }

        /** A key made by the machine's own `ssh-keygen` in OpenSSH's file format, or null when there is none. */
        fun openSsh(
            dir: File,
            type: String,
            passphrase: String? = null,
        ): ClientKey? {
            val keygen = listOf("/usr/bin/ssh-keygen", "/usr/local/bin/ssh-keygen").firstOrNull { File(it).canExecute() } ?: return null
            val file = File(dir, "id_$type-${System.nanoTime()}")
            val process =
                ProcessBuilder(keygen, "-q", "-t", type, "-N", passphrase.orEmpty(), "-f", file.path).redirectErrorStream(true).start()
            process.inputStream.readBytes()
            if (process.waitFor() != 0) return null
            val line = File(file.path + ".pub").readText().trim()
            val key = AuthorizedKeyEntry.parseAuthorizedKeyEntry(line).resolvePublicKey(null, PublicKeyEntryResolver.IGNORING)
            return ClientKey(key, file, passphrase)
        }
    }
}

/**
 * An Apache MINA SSHD with an SFTP subsystem on loopback, rooted at a home folder: user `ann` signs in with the
 * password `pw` or with [authorized] keys. The host key is generated per server, so two fixtures never share one.
 */
internal class SftpServerFixture(
    val home: File = Files.createTempDirectory("anydm-sftp").toFile(),
    authorized: List<ClientKey> = emptyList(),
) : AutoCloseable {
    private val server: SshServer = SshServer.setUpDefaultServer()
    val port: Int

    init {
        server.host = "127.0.0.1"
        server.port = 0
        server.keyPairProvider =
            SimpleGeneratorHostKeyProvider(File(home.parentFile, "hostkey-${home.name}.ser").toPath()).apply {
                algorithm = "EC"
                keySize = 256
            }
        server.passwordAuthenticator =
            org.apache.sshd.server.auth.password
                .PasswordAuthenticator { user, password, _ -> user == "ann" && password == "pw" }
        server.publickeyAuthenticator =
            org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator { user, key, _ ->
                user == "ann" &&
                    authorized.any {
                        org.apache.sshd.common.config.keys.KeyUtils
                            .compareKeys(it.publicKey, key)
                    }
            }
        server.subsystemFactories = listOf(SftpSubsystemFactory())
        server.fileSystemFactory = VirtualFileSystemFactory(home.toPath())
        server.start()
        port = server.port
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
        server.stop(true)
        home.deleteRecursively()
        File(home.parentFile, "hostkey-${home.name}.ser").delete()
    }
}
