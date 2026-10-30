package dev.anydm.engine.http

import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.media.CollectingIo
import dev.anydm.engine.protocol.StepResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test

class HttpFetchTest {
    private val server = FaultServer()
    private val protocol = HttpProtocol(OkHttpTransport())
    private val body = ByteArray(200_000) { (it % 251).toByte() }

    @AfterTest
    fun close() = server.close()

    private fun ok(result: StepResult<ByteArray>) = result.shouldBeInstanceOf<StepResult.Ok<ByteArray>>().value

    private fun failed(result: StepResult<*>) = result.shouldBeInstanceOf<StepResult.Failed>()

    @Test
    fun fetchFragmentReturnsTheBodyAndCountsIt() =
        runBlocking<Unit> {
            server.serve("/f", body)
            val io = CollectingIo()

            ok(protocol.fetchFragment(server.url("/f"), null, null, null, io)).contentEquals(body) shouldBe true

            io.limiter.bytes shouldBe body.size.toLong()
            io.meter.bytes shouldBe body.size.toLong()
        }

    @Test
    fun aRangeIsSliced() =
        runBlocking<Unit> {
            server.serve("/f", body)
            val wanted = body.copyOfRange(1000, 2000)

            ok(protocol.fetchFragment(server.url("/f"), null, 1000, 2000, CollectingIo())).contentEquals(wanted) shouldBe true
            server.requests.last().headers["Range"] shouldBe "bytes=1000-1999"

            server.fault("/f", Fault.IgnoreRanges)
            ok(protocol.fetchFragment(server.url("/f"), null, 1000, 2000, CollectingIo())).contentEquals(wanted) shouldBe true
        }

    @Test
    fun aShortBodyIsTransient() =
        runBlocking<Unit> {
            server.serve("/f", body)
            server.fault("/f", Fault.DropAfter(10))

            failed(protocol.fetchFragment(server.url("/f"), null, null, null, CollectingIo())).errorClass shouldBe ErrorClass.TRANSIENT
        }

    @Test
    fun aTextBodyIsRefused() =
        runBlocking<Unit> {
            server.serve("/f", "<html>blocked</html>".toByteArray(), contentType = "text/html; charset=utf-8")

            val result = failed(protocol.fetchFragment(server.url("/f"), null, null, null, CollectingIo()))

            result.errorClass shouldBe ErrorClass.TRANSIENT
            result.message shouldContain "text/html"
        }

    @Test
    fun a403IsAuthAndA410IsGone() =
        runBlocking<Unit> {
            server.serve("/f", body)
            server.fault("/f", Fault.Status(403))
            failed(protocol.fetchFragment(server.url("/f"), null, null, null, CollectingIo())).errorClass shouldBe ErrorClass.AUTH
            server.fault("/f", Fault.Status(410))
            failed(protocol.fetchFragment(server.url("/f"), null, null, null, CollectingIo())).errorClass shouldBe ErrorClass.GONE
        }

    @Test
    fun fetchTextReadsAPlaylistAndRefusesOneOverTheLimit() =
        runBlocking<Unit> {
            server.serve("/p.m3u8", "#EXTM3U\n#EXT-X-ENDLIST\n".toByteArray())

            val text = protocol.fetchText(server.url("/p.m3u8"), null).shouldBeInstanceOf<StepResult.Ok<FetchedText>>().value
            text.text shouldBe "#EXTM3U\n#EXT-X-ENDLIST\n"
            text.finalUrl shouldBe server.url("/p.m3u8")

            failed(protocol.fetchText(server.url("/p.m3u8"), null, limit = 10)).message shouldContain "bigger"
        }

    @Test
    fun withSlotHoldsAndReleasesASlot() =
        runBlocking<Unit> {
            val url = server.url("/f")
            val origin = originOf(url)

            protocol.withSlot(url) { protocol.slots.held(origin) shouldBe 1 }
            protocol.slots.held(origin) shouldBe 0

            runCatching { protocol.withSlot(url) { error("boom") } }
            protocol.slots.held(origin) shouldBe 0
        }

    @Test
    fun anEmptyBodyIsTransient() =
        runBlocking<Unit> {
            server.serve("/f", ByteArray(0))

            failed(protocol.fetchFragment(server.url("/f"), null, null, null, CollectingIo())).errorClass shouldBe ErrorClass.TRANSIENT
        }

    @Test
    fun aFragmentOverTheCapIsRefused() =
        runBlocking<Unit> {
            server.serve("/f", body)

            val result = failed(protocol.fetchFragment(server.url("/f"), null, null, null, CollectingIo(), maxBytes = 1000))

            result.errorClass shouldBe null
            result.message shouldContain "bigger"
        }

    @Test
    fun aMisLabelledMediaFileIsKept() =
        runBlocking<Unit> {
            val ts = ByteArray(3000) { if (it % 188 == 0) 0x47 else 0x11 }
            server.serve("/f", ts, contentType = "text/plain")

            ok(protocol.fetchFragment(server.url("/f"), null, null, null, CollectingIo())).contentEquals(ts) shouldBe true
        }

    @Test
    fun anHtmlBodyUnderAMediaTypeIsRefused() =
        runBlocking<Unit> {
            server.serve("/f", "  <html><body>blocked</body></html>".toByteArray(), contentType = "video/mp2t")

            failed(protocol.fetchFragment(server.url("/f"), null, null, null, CollectingIo())).errorClass shouldBe ErrorClass.TRANSIENT
        }

    @Test
    fun anInvertedRangeIsRefusedNotThrown() =
        runBlocking<Unit> {
            server.serve("/f", body)
            server.fault("/f", Fault.IgnoreRanges)

            failed(protocol.fetchFragment(server.url("/f"), null, 200, 100, CollectingIo()))
        }
}
