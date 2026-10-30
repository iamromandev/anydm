package dev.anydm.engine.os

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** Starting and stopping the hotkey with its setting, and handling its presses (OSI-06), with a fake key capture. */
class HotkeyServiceTest {
    private class FakeHotkey : GlobalHotkey {
        var starts = 0
        var stops = 0
        var running = false
        var result: HotkeyStatus = HotkeyStatus.Active
        var failure: Throwable? = null
        var stopFailure: Throwable? = null
        var press: (() -> Unit)? = null

        override suspend fun start(onPress: () -> Unit): HotkeyStatus {
            failure?.let { throw it }
            starts++
            if (result != HotkeyStatus.Active) return result
            check(!running) { "started twice" }
            running = true
            press = onPress
            return result
        }

        override suspend fun stop() {
            stops++
            running = false
            press = null
            stopFailure?.let { throw it }
        }
    }

    private class Fixture(
        test: TestScope,
    ) {
        val hotkey = FakeHotkey()
        val enabled = MutableStateFlow(false)
        val handled = mutableListOf<Int>()
        var count = 0
        var action: suspend () -> Unit = {}
        val service =
            HotkeyService(hotkey) {
                handled += ++count
                action()
            }

        val job = test.backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { service.run(enabled) }
    }

    private fun serviceTest(body: suspend TestScope.(Fixture) -> Unit) =
        runTest {
            val f = Fixture(this)
            runCurrent()
            body(f)
        }

    @Test
    fun itIsOffUntilTheSettingIsOn() =
        serviceTest { f ->
            f.service.status.value shouldBe HotkeyStatus.Off
            f.hotkey.starts shouldBe 0
        }

    @Test
    fun switchedOnItStartsAndIsActive() =
        serviceTest { f ->
            f.enabled.value = true
            runCurrent()

            f.hotkey.starts shouldBe 1
            f.service.status.value shouldBe HotkeyStatus.Active
        }

    @Test
    fun eachPressIsHandledInTheOrderItCame() =
        serviceTest { f ->
            f.enabled.value = true
            runCurrent()

            repeat(3) { f.hotkey.press!!() }
            runCurrent()

            f.handled shouldContainExactly listOf(1, 2, 3)
        }

    @Test
    fun aSlowPressDoesNotBlockTheCaptureAndTheNextOnesWait() =
        serviceTest { f ->
            f.enabled.value = true
            runCurrent()
            val gate = CompletableDeferred<Unit>()
            f.action = { if (f.count == 1) gate.await() }

            repeat(3) { f.hotkey.press!!() } // returns at once, whatever the first press is doing
            runCurrent()
            f.handled shouldContainExactly listOf(1)

            gate.complete(Unit)
            runCurrent()
            f.handled shouldContainExactly listOf(1, 2, 3)
        }

    @Test
    fun aPressThatFailsDoesNotStopTheNext() =
        serviceTest { f ->
            f.enabled.value = true
            runCurrent()
            f.action = { if (f.count == 1) error("could not add") }

            f.hotkey.press!!()
            f.hotkey.press!!()
            runCurrent()

            f.handled shouldContainExactly listOf(1, 2)
        }

    @Test
    fun switchedOffItStopsAndPressesDoNothing() =
        serviceTest { f ->
            f.enabled.value = true
            runCurrent()
            val press = f.hotkey.press!!

            f.enabled.value = false
            runCurrent()
            press()
            runCurrent()

            f.hotkey.stops shouldBe 1
            f.hotkey.running shouldBe false
            f.service.status.value shouldBe HotkeyStatus.Off
            f.handled shouldBe emptyList()
        }

    @Test
    fun switchedOnAgainItStartsCleanlyOnce() =
        serviceTest { f ->
            repeat(3) {
                f.enabled.value = true
                runCurrent()
                f.enabled.value = false
                runCurrent()
            }
            f.enabled.value = true
            runCurrent()

            f.hotkey.starts shouldBe 4
            f.hotkey.stops shouldBe 3
            f.hotkey.running shouldBe true
            f.hotkey.press!!()
            runCurrent()
            f.handled shouldContainExactly listOf(1)
        }

    @Test
    fun aHotkeyThatCannotStartSaysWhyAndNothingElseBreaks() =
        serviceTest { f ->
            for (status in listOf(HotkeyStatus.NeedsPermission, HotkeyStatus.Unsupported("Wayland"), HotkeyStatus.Failed("no library"))) {
                f.hotkey.result = status
                f.enabled.value = false
                runCurrent()
                f.enabled.value = true
                runCurrent()

                f.service.status.value shouldBe status
            }
            f.hotkey.press shouldBe null
            f.handled shouldBe emptyList()
        }

    @Test
    fun aStopThatThrowsDoesNotTakeTheServiceDown() =
        serviceTest { f ->
            f.hotkey.stopFailure = IllegalStateException("could not let go")
            f.enabled.value = true
            runCurrent()

            f.enabled.value = false
            runCurrent()
            f.hotkey.stopFailure = null
            f.enabled.value = true
            runCurrent()

            f.service.status.value shouldBe HotkeyStatus.Active
            f.hotkey.press!!()
            runCurrent()
            f.handled shouldContainExactly listOf(1)
        }

    @Test
    fun anErrorInAPressDoesNotEndTheNextOnes() =
        serviceTest { f ->
            f.enabled.value = true
            runCurrent()
            f.action = { if (f.count == 1) throw NoClassDefFoundError("odd") }

            f.hotkey.press!!()
            f.hotkey.press!!()
            runCurrent()

            f.handled shouldContainExactly listOf(1, 2)
        }

    @Test
    fun cancellingTheServiceLetsGoOfTheKeys() =
        runTest {
            val f = Fixture(this)
            f.enabled.value = true
            runCurrent()
            f.hotkey.running shouldBe true

            f.job.cancel()
            runCurrent()

            f.hotkey.running shouldBe false
            f.hotkey.stops shouldBe 1
        }

    @Test
    fun aCaptureThatThrowsIsAFailureNotACrash() =
        serviceTest { f ->
            f.hotkey.failure = UnsatisfiedLinkError("no native library")
            f.enabled.value = true
            runCurrent()

            f.service.status.value shouldBe HotkeyStatus.Failed("no native library")
        }

    @Test
    fun onceItIsSwitchedOffAfterAFailureItIsOff() =
        serviceTest { f ->
            f.hotkey.result = HotkeyStatus.NeedsPermission
            f.enabled.value = true
            runCurrent()
            f.service.status.value shouldBe HotkeyStatus.NeedsPermission

            f.enabled.value = false
            runCurrent()

            f.service.status.value shouldBe HotkeyStatus.Off
        }
}
