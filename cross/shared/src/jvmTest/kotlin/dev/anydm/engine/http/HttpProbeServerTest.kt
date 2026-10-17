package dev.anydm.engine.http

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.core.aDownload
import dev.anydm.engine.protocol.ProbeRequest
import dev.anydm.engine.protocol.StepResult
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test

class HttpProbeServerTest {
    private val server = FaultServer()
    private val protocol = HttpProtocol(OkHttpTransport())
    private val file = ByteArray(5000) { it.toByte() }
    private val lastModified = "Wed, 21 Oct 2015 07:28:00 GMT"

    @AfterTest
    fun close() = server.close()

    private suspend fun probe(path: String): HttpProbe {
        val mirror = Mirror(MirrorId(1), DownloadId(1), server.url(path), 0)
        return protocol.probe(ProbeRequest(aDownload(), mirror)).shouldBeInstanceOf<StepResult.Ok<HttpProbe>>().value
    }

    @Test
    fun aRangedFile() =
        runBlocking<Unit> {
            server.serve("/a.bin", file, etag = "\"v1\"", contentType = "application/octet-stream")
            val probe = probe("/a.bin")
            probe.size shouldBe 5000
            probe.rangesSupported shouldBe true
            probe.name shouldBe "a.bin"
            probe.finalUrl shouldBe server.url("/a.bin")
            server.requests.single().headers["Range"] shouldBe "bytes=0-1023"
        }

    @Test
    fun aFileWithoutRanges() =
        runBlocking<Unit> {
            server.serve("/a.bin", file)
            server.fault("/a.bin", Fault.IgnoreRanges)
            val probe = probe("/a.bin")
            probe.size shouldBe 5000
            probe.rangesSupported shouldBe false
        }

    @Test
    fun etagOnly() =
        runBlocking<Unit> {
            server.serve("/a.bin", file, etag = "\"v1\"")
            probe("/a.bin").validator shouldBe "etag:\"v1\""
        }

    @Test
    fun lastModifiedOnly() =
        runBlocking<Unit> {
            server.serve("/a.bin", file, lastModified = lastModified)
            probe("/a.bin").validator shouldBe "last-modified:$lastModified"
            server.serve("/b.bin", file)
            probe("/b.bin").validator.shouldBeNull()
        }
}
