package dev.anydm.engine.os

import com.github.kwhat.jnativehook.NativeHookException
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import dev.anydm.engine.platform.DesktopOs
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** The desktop hotkey around a fake key capture (OSI-06): which keys fire it, and what its failures mean. */
class NativeHotkeyTest {
    private class FakeHook : NativeHook {
        var registered = 0
        var unregistered = 0
        var failure: Throwable? = null
        var onKey: ((Key, Boolean) -> Unit)? = null

        override fun register(onKey: (Key, Boolean) -> Unit) {
            failure?.let { throw it }
            registered++
            this.onKey = onKey
        }

        override fun unregister() {
            unregistered++
            onKey = null
        }

        fun press(vararg keys: Key) = keys.forEach { onKey?.invoke(it, true) }

        fun release(vararg keys: Key) = keys.forEach { onKey?.invoke(it, false) }
    }

    private fun hotkey(
        hook: FakeHook,
        os: DesktopOs = DesktopOs.LINUX,
        env: Map<String, String> = mapOf("DISPLAY" to ":0"),
    ) = NativeHotkey(hook, os, env)

    @Test
    fun theCombinationFiresOncePerPressWhateverTheKeyDoes() =
        runTest {
            val hook = FakeHook()
            var fired = 0
            hotkey(hook).start { fired++ } shouldBe HotkeyStatus.Active

            hook.press(Key.CTRL, Key.SHIFT, Key.D)
            repeat(10) { hook.press(Key.D) }
            hook.release(Key.D)
            hook.press(Key.D)

            fired shouldBe 2
        }

    @Test
    fun macUsesCommandAndTheOthersUseControl() =
        runTest {
            val mac = FakeHook()
            var macFired = 0
            hotkey(mac, DesktopOs.MACOS).start { macFired++ }
            mac.press(Key.CTRL, Key.SHIFT, Key.D)
            macFired shouldBe 0
            mac.release(Key.D)
            mac.press(Key.META)
            mac.press(Key.D)
            macFired shouldBe 1

            for (os in listOf(DesktopOs.WINDOWS, DesktopOs.LINUX)) {
                val hook = FakeHook()
                var fired = 0
                hotkey(hook, os).start { fired++ }
                hook.press(Key.META, Key.SHIFT, Key.D)
                fired shouldBe 0
                hook.release(Key.D)
                hook.press(Key.CTRL, Key.D)
                fired shouldBe 1
            }
        }

    @Test
    fun underWaylandItIsUnsupportedAndNothingIsRegistered() =
        runTest {
            val hook = FakeHook()

            val status = hotkey(hook, DesktopOs.LINUX, mapOf("XDG_SESSION_TYPE" to "wayland", "DISPLAY" to ":0")).start { }

            status.shouldBeUnsupported()
            hook.registered shouldBe 0
        }

    @Test
    fun waylandMeansNothingOnTheOtherSystems() =
        runTest {
            hotkey(FakeHook(), DesktopOs.MACOS, mapOf("XDG_SESSION_TYPE" to "wayland")).start { } shouldBe HotkeyStatus.Active
            hotkey(FakeHook(), DesktopOs.WINDOWS, mapOf("XDG_SESSION_TYPE" to "wayland")).start { } shouldBe HotkeyStatus.Active
        }

    @Test
    fun aWaylandDisplayAloneIsWaylandToo() =
        runTest {
            hotkey(FakeHook(), DesktopOs.LINUX, mapOf("WAYLAND_DISPLAY" to "wayland-0", "DISPLAY" to ":0")).start { }.shouldBeUnsupported()
        }

    @Test
    fun linuxWithoutAnXServerIsUnsupported() =
        runTest {
            hotkey(FakeHook(), DesktopOs.LINUX, emptyMap()).start { }.shouldBeUnsupported()
        }

    @Test
    fun aRefusedHookOnMacMeansThePermissionIsMissing() =
        runTest {
            for (code in listOf(NativeHookException.DARWIN_AXAPI_DISABLED, NativeHookException.DARWIN_CREATE_EVENT_PORT)) {
                val hook = FakeHook().also { it.failure = NativeHookException(code, "Failed to enable access for assistive devices") }

                hotkey(hook, DesktopOs.MACOS).start { } shouldBe HotkeyStatus.NeedsPermission
            }
        }

    @Test
    fun otherRefusalsOnMacAreFailuresNotAMissingPermission() =
        runTest {
            val hook =
                FakeHook().also {
                    it.failure =
                        NativeHookException(
                            NativeHookException.DARWIN_CREATE_RUN_LOOP_SOURCE,
                            "no run loop",
                        )
                }

            hotkey(hook, DesktopOs.MACOS).start { } shouldBe HotkeyStatus.Failed("no run loop")
            hook.failure = NativeHookException("odd")
            hotkey(hook, DesktopOs.MACOS).start { } shouldBe HotkeyStatus.Failed("odd")
        }

    @Test
    fun aRefusedHookElsewhereIsAFailureWithItsReason() =
        runTest {
            val hook = FakeHook().also { it.failure = NativeHookException("XOpenDisplay failed") }

            hotkey(hook, DesktopOs.LINUX).start { } shouldBe HotkeyStatus.Failed("XOpenDisplay failed")
            hotkey(hook, DesktopOs.WINDOWS).start { } shouldBe HotkeyStatus.Failed("XOpenDisplay failed")
        }

    @Test
    fun aMissingNativeLibraryIsUnsupportedNotACrash() =
        runTest {
            for (error in listOf(
                UnsatisfiedLinkError("no native"),
                NoClassDefFoundError("GlobalScreen"),
                ExceptionInInitializerError("x"),
            )) {
                val hook = FakeHook().also { it.failure = error }

                val status = hotkey(hook).start { }
                status.shouldBeUnsupported()
                // Why is kept: an unpacking that failed shows up as exactly this.
                (status as HotkeyStatus.Unsupported).reason.contains(error.message ?: "") shouldBe true
            }
        }

    @Test
    fun anythingElseThatGoesWrongIsAFailure() =
        runTest {
            val hook = FakeHook().also { it.failure = IllegalStateException("odd") }

            hotkey(hook).start { } shouldBe HotkeyStatus.Failed("odd")
        }

    @Test
    fun stoppingLetsGoAndPressesAfterItDoNothing() =
        runTest {
            val hook = FakeHook()
            var fired = 0
            val hotkey = hotkey(hook)
            hotkey.start { fired++ }
            val onKey = hook.onKey!!

            hotkey.stop()
            onKey(Key.CTRL, true)
            onKey(Key.SHIFT, true)
            onKey(Key.D, true)

            hook.unregistered shouldBe 1
            fired shouldBe 0
        }

    @Test
    fun stoppingWhatIsNotStartedChangesNothing() =
        runTest {
            val hook = FakeHook()

            hotkey(hook).stop()

            hook.unregistered shouldBe 0
        }

    @Test
    fun startedAgainItForgetsWhatWasHeldBefore() =
        runTest {
            val hook = FakeHook()
            var fired = 0
            val hotkey = hotkey(hook)
            hotkey.start { fired++ }
            hook.press(Key.CTRL, Key.SHIFT)
            hotkey.stop()

            hotkey.start { fired++ }
            hook.press(Key.D)

            fired shouldBe 0
        }

    @Test
    fun theListenerPassesOnTheFourKeysWhicheverSideAndNothingElse() {
        val seen = mutableListOf<Pair<Key, Boolean>>()
        val listener = listenerFor { key, down -> seen += key to down }

        fun event(
            id: Int,
            code: Int,
            location: Int = NativeKeyEvent.KEY_LOCATION_STANDARD,
        ) = NativeKeyEvent(id, 0, 0, code, NativeKeyEvent.CHAR_UNDEFINED, location)

        listener.nativeKeyPressed(event(NativeKeyEvent.NATIVE_KEY_PRESSED, NativeKeyEvent.VC_SHIFT, NativeKeyEvent.KEY_LOCATION_RIGHT))
        listener.nativeKeyReleased(event(NativeKeyEvent.NATIVE_KEY_RELEASED, NativeKeyEvent.VC_SHIFT, NativeKeyEvent.KEY_LOCATION_RIGHT))
        listener.nativeKeyPressed(event(NativeKeyEvent.NATIVE_KEY_PRESSED, NativeKeyEvent.VC_A))
        listener.nativeKeyPressed(event(NativeKeyEvent.NATIVE_KEY_PRESSED, NativeKeyEvent.VC_ENTER))
        listener.nativeKeyReleased(event(NativeKeyEvent.NATIVE_KEY_RELEASED, NativeKeyEvent.VC_A))
        listener.nativeKeyPressed(event(NativeKeyEvent.NATIVE_KEY_PRESSED, NativeKeyEvent.VC_D))

        seen shouldBe listOf(Key.SHIFT to true, Key.SHIFT to false, Key.D to true)
    }

    @Test
    fun onlyTheFourKeysArePassedOn() {
        keyOf(NativeKeyEvent.VC_SHIFT) shouldBe Key.SHIFT
        keyOf(NativeKeyEvent.VC_CONTROL) shouldBe Key.CTRL
        keyOf(NativeKeyEvent.VC_META) shouldBe Key.META
        keyOf(NativeKeyEvent.VC_D) shouldBe Key.D
        keyOf(NativeKeyEvent.VC_A) shouldBe null
        keyOf(NativeKeyEvent.VC_ENTER) shouldBe null
        keyOf(NativeKeyEvent.VC_ALT) shouldBe null
        keyOf(0) shouldBe null
    }

    private fun HotkeyStatus.shouldBeUnsupported() {
        (this is HotkeyStatus.Unsupported) shouldBe true
    }
}
