package dev.anydm.engine.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ChecksumTest {
    private val sha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    @Test
    fun namedForms() {
        for (text in listOf("sha256:$sha", "SHA-256=$sha", " sha-256:${sha.uppercase()} ", "Sha256=$sha")) {
            withClue(text) { Checksum.parse(text) shouldBe Checksum(ChecksumAlgorithm.SHA256, sha) }
        }
        Checksum.parse("sha1:da39a3ee5e6b4b0d3255bfef95601890afd80709")!!.algorithm shouldBe ChecksumAlgorithm.SHA1
        Checksum.parse("sha-1=da39a3ee5e6b4b0d3255bfef95601890afd80709")!!.algorithm shouldBe ChecksumAlgorithm.SHA1
        Checksum.parse("md5:d41d8cd98f00b204e9800998ecf8427e")!!.algorithm shouldBe ChecksumAlgorithm.MD5
    }

    @Test
    fun bareHexByLength() {
        Checksum.parse("D41D8CD98F00B204E9800998ECF8427E") shouldBe Checksum(ChecksumAlgorithm.MD5, "d41d8cd98f00b204e9800998ecf8427e")
        Checksum.parse("da39a3ee5e6b4b0d3255bfef95601890afd80709")!!.algorithm shouldBe ChecksumAlgorithm.SHA1
        Checksum.parse(sha)!!.algorithm shouldBe ChecksumAlgorithm.SHA256
    }

    @Test
    fun rejects() {
        for (bad in listOf("", "  ", "sha256:abc", "md5:$sha", "crc32:1234abcd", "xyz", "g".repeat(32), "sha256:")) {
            withClue(bad) { Checksum.parse(bad).shouldBeNull() }
        }
        shouldThrow<IllegalArgumentException> { Checksum(ChecksumAlgorithm.SHA256, sha.uppercase()) }
        shouldThrow<IllegalArgumentException> { Checksum(ChecksumAlgorithm.MD5, sha) }
    }

    @Test
    fun roundTrips() {
        val checksum = Checksum(ChecksumAlgorithm.SHA256, sha)
        checksum.toString() shouldBe "sha256:$sha"
        Checksum.parse(checksum.toString()) shouldBe checksum
        Checksum.parse(Checksum(ChecksumAlgorithm.SHA1, "da39a3ee5e6b4b0d3255bfef95601890afd80709").toString())!!.algorithm shouldBe
            ChecksumAlgorithm.SHA1
    }
}
