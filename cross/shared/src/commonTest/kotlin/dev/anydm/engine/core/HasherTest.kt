package dev.anydm.engine.core

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class HasherTest {
    private val vectors =
        mapOf(
            ChecksumAlgorithm.MD5 to ("d41d8cd98f00b204e9800998ecf8427e" to "900150983cd24fb0d6963f7d28e17f72"),
            ChecksumAlgorithm.SHA1 to ("da39a3ee5e6b4b0d3255bfef95601890afd80709" to "a9993e364706816aba3e25717850c26c9cd0d89d"),
            ChecksumAlgorithm.SHA256 to
                (
                    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" to
                        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
                ),
        )

    @Test
    fun knownVectors() {
        for ((algorithm, expected) in vectors) {
            withClue(algorithm) {
                hasherFor(algorithm).hex() shouldBe expected.first
                hasherFor(algorithm).also { it.update("abc".encodeToByteArray()) }.hex() shouldBe expected.second
            }
        }
    }

    @Test
    fun piecesHashLikeTheWhole() {
        val bytes = "xabcx".encodeToByteArray()
        for ((algorithm, expected) in vectors) {
            withClue(algorithm) {
                val hasher = hasherFor(algorithm)
                hasher.update(bytes, 1, 1)
                hasher.update(bytes, 2, 1)
                hasher.update(bytes, 3, 1)
                hasher.hex() shouldBe expected.second
            }
        }
    }
}
