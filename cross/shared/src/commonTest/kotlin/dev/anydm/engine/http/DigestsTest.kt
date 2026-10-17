package dev.anydm.engine.http

import dev.anydm.engine.core.Checksum
import dev.anydm.engine.core.ChecksumAlgorithm.SHA1
import dev.anydm.engine.core.ChecksumAlgorithm.SHA256
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** Checksums a server gives in `Digest` (RFC 3230) and `Repr-Digest` (RFC 9530) (HTTP-14). */
class DigestsTest {
    private val sha256 = Checksum(SHA256, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    private val sha1 = Checksum(SHA1, "a9993e364706816aba3e25717850c26c9cd0d89d")
    private val b64Sha256 = "ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0="
    private val b64Sha1 = "qZk+NkcGgWq6PiVxeFDCbJzQ2J0="
    private val b64Md5 = "kAFQmDzST7DWlj99KOF/cg=="

    private fun of(vararg headers: Pair<String, String>) = checksumOf(Headers(headers.toList()))

    @Test
    fun digestEntries() {
        of("Digest" to "SHA-256=$b64Sha256") shouldBe sha256
        of("Digest" to "md5=$b64Md5, SHA=$b64Sha1") shouldBe sha1
        of("Digest" to "MD5=$b64Md5", "Digest" to "sha-256=${b64Sha256.trimEnd('=')}") shouldBe sha256
    }

    @Test
    fun reprDigest() {
        of("Repr-Digest" to "sha-256=:$b64Sha256:") shouldBe sha256
        of("Repr-Digest" to "sha=:$b64Sha1:, md5=:$b64Md5:") shouldBe sha1
    }

    @Test
    fun unknownEntriesAreSkipped() {
        of("Digest" to "UNIXsum=123, SHA-256=$b64Sha256") shouldBe sha256
    }

    @Test
    fun badEntriesAndOtherHeadersGiveNothing() {
        for (header in listOf(
            "Digest" to "SHA-256=not*base64",
            "Digest" to "SHA-256=$b64Sha1",
            "Content-Digest" to "sha-256=:$b64Sha256:",
            "Content-MD5" to b64Md5,
        )) {
            withClue(header) { of(header).shouldBeNull() }
        }
        of().shouldBeNull()
    }
}
