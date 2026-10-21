package dev.anydm.engine.platform

import dev.anydm.engine.log.Log
import dev.anydm.engine.log.RecordingLogSink
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** Only a fake [ExecutionStateApi] is used: the real SetThreadExecutionState is never called from a test. */
class WindowsKeepAwakeTest {
    private class FakeApi(
        var result: Int = 1,
    ) : ExecutionStateApi {
        val flags = mutableListOf<Int>()
        val threads = mutableListOf<Thread>()

        override fun set(flags: Int): Int {
            this.flags += flags
            threads += Thread.currentThread()
            return result
        }
    }

    private val sink = RecordingLogSink()

    private fun TestScope.keeper(api: ExecutionStateApi) = WindowsKeepAwake(api, Log(sink, virtualClock()))

    @Test
    fun acquireSetsContinuousAndSystemRequired() =
        runTest {
            val api = FakeApi()

            keeper(api).acquire()

            api.flags shouldBe listOf(0x80000001.toInt())
        }

    @Test
    fun releaseSetsContinuousOnly() =
        runTest {
            val api = FakeApi()
            val keep = keeper(api)

            keep.acquire()
            keep.release()

            api.flags shouldBe listOf(0x80000001.toInt(), 0x80000000.toInt())
        }

    @Test
    fun bothCallsRunOnOneThreadThatIsNotTheCallers() =
        runTest {
            val api = FakeApi()
            val keep = keeper(api)

            keep.acquire()
            keep.release()
            keep.acquire()

            api.threads.toSet().size shouldBe 1
            api.threads.first() shouldNotBe Thread.currentThread()
            api.threads.first().name shouldBe "anydm-keep-awake"
            api.threads.first().isDaemon shouldBe true
        }

    @Test
    fun acquireTwiceCallsOnce() =
        runTest {
            val api = FakeApi()
            val keep = keeper(api)

            keep.acquire()
            keep.acquire()

            api.flags.size shouldBe 1
        }

    @Test
    fun releaseWithoutAcquireMakesNoCall() =
        runTest {
            val api = FakeApi()

            keeper(api).release()

            api.flags shouldBe emptyList()
        }

    @Test
    fun aZeroResultWarnsOnceAndDoesNotThrow() =
        runTest {
            val api = FakeApi(result = 0)
            val keep = keeper(api)

            keep.acquire()
            keep.acquire()
            keep.release()

            sink.lines.size shouldBe 1
            sink.lines.single().contains("keep awake unavailable") shouldBe true
        }

    @Test
    fun aThrowingApiWarnsOnceAndDoesNotThrow() =
        runTest {
            val api =
                object : ExecutionStateApi {
                    override fun set(flags: Int): Int = throw UnsatisfiedLinkError("no kernel32")
                }
            val keep = keeper(api)

            keep.acquire()
            keep.acquire()

            sink.lines.size shouldBe 1
        }
}
