package dev.anydm.engine.http

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.File
import dev.anydm.engine.core.FileId
import dev.anydm.engine.core.Segment
import dev.anydm.engine.core.SegmentId
import dev.anydm.engine.protocol.TransferFile
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test

/** RangeTransfer on a multi-threaded dispatcher, as StepRunner runs it in production. */
class RangeTransferThreadsTest {
    // Final review: two workers that got a 200 at once could both keep it, leaking one body.
    @Test
    fun simultaneousIgnoredRangesCloseEveryBody() =
        runBlocking<Unit> {
            val size = 4L shl 20
            val content = ByteArray(size.toInt()) { it.toByte() }
            val segments = planBlocks(size, 4).mapIndexed { i, s -> Segment(SegmentId(i + 1L), FileId(1), s.start, s.end) }
            val file = TransferFile(File(FileId(1), DownloadId(1), 0, "f.bin", size), segments, Path("/d/p"), Path("/d/f"))
            repeat(300) {
                val bodies = Collections.synchronizedList(mutableListOf<BytesBody>())
                val arrived = AtomicInteger()
                val all = CompletableDeferred<Unit>()
                val transport =
                    FakeTransport { request ->
                        if (request.headers.any { it.first == "Range" }) {
                            if (arrived.incrementAndGet() == 4) all.complete(Unit)
                            all.await()
                        }
                        bytesBody(content).also { bodies += it }.let { response(200, body = it) }
                    }
                withContext(Dispatchers.Default) {
                    RangeTransfer(transport, "https://h/f.bin", emptyList(), RecordingIo(size, segments), 4).run(file)
                }
                bodies.all { it.closed } shouldBe true
            }
        }
}
