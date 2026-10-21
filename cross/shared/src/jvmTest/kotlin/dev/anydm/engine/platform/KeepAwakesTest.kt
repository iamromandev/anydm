package dev.anydm.engine.platform

import dev.anydm.engine.log.Log
import dev.anydm.engine.log.RecordingLogSink
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class KeepAwakesTest {
    @Test
    fun eachOsGetsItsKind() =
        runTest {
            val runner = FakeProcessRunner(emptyList())
            val api =
                object : ExecutionStateApi {
                    override fun set(flags: Int): Int = error("no Windows call in this test")
                }
            val log = Log(RecordingLogSink(), virtualClock())

            keepAwakeFor(DesktopOs.of("Mac OS X"), runner, 1, api, log).shouldBeInstanceOf<HeldProcessKeepAwake>()
            keepAwakeFor(DesktopOs.of("Linux"), runner, 1, api, log).shouldBeInstanceOf<HeldProcessKeepAwake>()
            keepAwakeFor(DesktopOs.of("Windows 11"), runner, 1, api, log).shouldBeInstanceOf<WindowsKeepAwake>()
        }
}
