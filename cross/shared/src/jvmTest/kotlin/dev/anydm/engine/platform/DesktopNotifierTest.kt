package dev.anydm.engine.platform

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours

/** What each OS is asked to show (OSI-09), with a scripted process runner. */
class DesktopNotifierTest {
    private val ok = FakeProcessRunner.Scripted(listOf("osascript"))
    private val okLinux = FakeProcessRunner.Scripted(listOf("notify-send"))

    @Test
    fun macOsAsksAppleScript() =
        runTest {
            val runner = FakeProcessRunner(listOf(ok))

            DesktopNotifier(runner, DesktopOs.MACOS) { _, _ -> }.notify("Download finished", "a.zip")

            runner.started.map { it.command } shouldContainExactly
                listOf(listOf("osascript", "-e", "display notification \"a.zip\" with title \"Download finished\""))
        }

    @Test
    fun quotesAndBackslashesStayInsideTheString() =
        runTest {
            val runner = FakeProcessRunner(listOf(ok))

            DesktopNotifier(
                runner,
                DesktopOs.MACOS,
            ) { _, _ -> }.notify("T\"itle", "He said \"hi\" \\ \" & (do shell script \"touch /tmp/x\") & \"")

            val script =
                runner.started
                    .single()
                    .command
                    .last()
            script shouldBe
                "display notification \"He said \\\"hi\\\" \\\\ \\\" & (do shell script \\\"touch /tmp/x\\\") & \\\"\" with title \"T\\\"itle\""
        }

    @Test
    fun linuxAsksNotifySendAndATitleThatLooksLikeAnOptionIsNotOne() =
        runTest {
            val runner = FakeProcessRunner(listOf(okLinux))

            DesktopNotifier(runner, DesktopOs.LINUX) { _, _ -> }.notify("--urgency=critical", "a.zip")

            runner.started.single().command shouldContainExactly
                listOf("notify-send", "--app-name=anydm", "--", "--urgency=critical", "a.zip")
        }

    @Test
    fun linuxNotificationsAreMarkupSoItsSignsAreEscaped() =
        runTest {
            val runner = FakeProcessRunner(listOf(okLinux))

            DesktopNotifier(runner, DesktopOs.LINUX) { _, _ -> }.notify("a & b", "<a href=\"https://evil\">Click</a>.zip")

            runner.started
                .single()
                .command
                .takeLast(2) shouldContainExactly
                listOf("a &amp; b", "&lt;a href=\"https://evil\"&gt;Click&lt;/a&gt;.zip")
        }

    @Test
    fun aProgramThatHangsDoesNotHoldTheCallerForLong() =
        runTest {
            val runner = FakeProcessRunner(listOf(FakeProcessRunner.Scripted(listOf("notify-send"), delay = 1.hours)))

            DesktopNotifier(runner, DesktopOs.LINUX) { _, _ -> }.notify("T", "m")

            // Virtual time: it gave up after a few seconds, not an hour.
            (testScheduler.currentTime < 60_000) shouldBe true
        }

    @Test
    fun windowsUsesTheTrayAndStartsNoProgram() =
        runTest {
            val runner = FakeProcessRunner(emptyList())
            val shown = mutableListOf<Pair<String, String>>()

            DesktopNotifier(
                runner,
                DesktopOs.WINDOWS,
            ) { title, message -> shown += title to message }.notify("Download failed", "a.zip: HTTP 404")

            shown shouldContainExactly listOf("Download failed" to "a.zip: HTTP 404")
            runner.started shouldBe emptyList()
        }

    @Test
    fun aMissingProgramIsNotAnError() =
        runTest {
            val runner = FakeProcessRunner(emptyList())

            DesktopNotifier(runner, DesktopOs.LINUX) { _, _ -> }.notify("T", "m")

            runner.started.size shouldBe 1
        }

    @Test
    fun aTrayThatBreaksIsNotAnError() =
        runTest {
            DesktopNotifier(FakeProcessRunner(emptyList()), DesktopOs.WINDOWS) { _, _ -> error("no tray") }.notify("T", "m")
        }
}
