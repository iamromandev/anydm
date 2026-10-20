package dev.anydm.engine.http

import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.protocol.StepResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test

class HttpFetchBytesTest {
    private val server = FaultServer()
    private val protocol = HttpProtocol(OkHttpTransport())

    @AfterTest
    fun close() = server.close()

    @Test
    fun theBytesComeBackUndecoded() =
        runBlocking<Unit> {
            val body = Random(3).nextBytes(5_000) + byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00)
            server.serve("/x.torrent", body)

            val got = protocol.fetchBytes(server.url("/x.torrent"), null, 1 shl 20)

            got.shouldBeInstanceOf<StepResult.Ok<ByteArray>>().value.toList() shouldBe body.toList()
        }

    @Test
    fun aMissingFileAndAServerErrorMapAsForText() =
        runBlocking<Unit> {
            server.serve("/gone.torrent", ByteArray(3))
            server.serve("/busy.torrent", ByteArray(3))
            server.fault("/gone.torrent", Fault.Status(404))
            server.fault("/busy.torrent", Fault.Status(503))

            val gone = protocol.fetchBytes(server.url("/gone.torrent"), null, 1 shl 20)
            val busy = protocol.fetchBytes(server.url("/busy.torrent"), null, 1 shl 20)
            val asText = protocol.fetchText(server.url("/gone.torrent"), null)

            gone.shouldBeInstanceOf<StepResult.Failed>().errorClass shouldBe (asText as StepResult.Failed).errorClass
            gone.errorClass shouldBe ErrorClass.GONE
            busy.shouldBeInstanceOf<StepResult.Failed>().errorClass shouldBe ErrorClass.TRANSIENT
        }

    @Test
    fun aBodyOverTheLimitFails() =
        runBlocking<Unit> {
            server.serve("/big.torrent", ByteArray(2_000))

            val failed = protocol.fetchBytes(server.url("/big.torrent"), null, 1_000).shouldBeInstanceOf<StepResult.Failed>()

            failed.message shouldContain "bigger than 1000 bytes"
            protocol.fetchBytes(server.url("/big.torrent"), null, 2_000).shouldBeInstanceOf<StepResult.Ok<ByteArray>>()
        }
}
