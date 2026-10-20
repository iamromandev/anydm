package dev.anydm.engine.store

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** How a torrent's per-file progress is stored: 8 bytes per file, big-endian. */
class FileDoneCodecTest {
    @Test
    fun theCodecRoundTrips() {
        val done = listOf(0L, 1L, 255L, 256L, Long.MAX_VALUE)

        decodeFileDone(encodeFileDone(done)) shouldBe done
        encodeFileDone(listOf(1L)).toList() shouldBe listOf<Byte>(0, 0, 0, 0, 0, 0, 0, 1)
    }

    @Test
    fun nothingStoredIsEmpty() {
        decodeFileDone(null) shouldBe emptyList()
        decodeFileDone(ByteArray(0)) shouldBe emptyList()
        encodeFileDone(emptyList()).size shouldBe 0
    }

    @Test
    fun aBlobOfTheWrongLengthReadsAsEmpty() {
        decodeFileDone(ByteArray(5)) shouldBe emptyList()
        decodeFileDone(ByteArray(17)) shouldBe emptyList()
    }

    @Test
    fun aNegativeValueReadsAsEmpty() {
        decodeFileDone(encodeFileDone(listOf(5L, -1L))) shouldBe emptyList()
    }
}
