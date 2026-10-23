package dev.anydm.engine.os

import dev.anydm.engine.EngineEvent
import dev.anydm.engine.EngineHarness
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.platform.Clipboard
import dev.anydm.engine.platform.Notifier
import dev.anydm.engine.store.StoreTestBase
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** What a press of the hotkey does with the clipboard (OSI-06), and what it says about it. */
abstract class HotkeyQueueTest : StoreTestBase() {
    private class Fixture(
        val h: EngineHarness,
        val heard: MutableList<EngineEvent.IncomingLink>,
    ) {
        var clipboard: String? = null
        var enabled = true
        var broken = false
        val told = mutableListOf<Pair<String, String>>()
        val queue =
            HotkeyQueue(
                h.engine,
                Clipboard { clipboard },
                Notifier { title, message ->
                    if (broken) error("no notification service")
                    told += title to message
                },
                { enabled },
            )
    }

    private fun hotkeyTest(body: suspend TestScope.(Fixture) -> Unit) =
        runTest {
            val h = EngineHarness(this, newStore())
            val heard = mutableListOf<EngineEvent.IncomingLink>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                h.engine.events.collect { if (it is EngineEvent.IncomingLink) heard += it }
            }
            h.engine.start()
            try {
                body(Fixture(h, heard))
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun aLinkOnTheClipboardIsAddedAndSaidSo() =
        hotkeyTest { f ->
            f.clipboard = "https://files.example.com/dir/a%20file.zip?token=1"

            f.queue.press()
            runCurrent()

            f.h.engine.downloads.value.size shouldBe 1
            f.heard.single().entry shouldBe EntryPoint.HOTKEY
            f.heard.single().kind shouldBe DownloadKind.DIRECT
            f.told shouldContainExactly listOf("anydm" to "Added: a file.zip")
        }

    @Test
    fun aMagnetLinkIsNamedByItsDisplayName() =
        hotkeyTest { f ->
            f.clipboard = "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335aa7c1367a88a&dn=Some+Show%20S01"

            f.queue.press()
            runCurrent()

            f.heard.single().kind shouldBe DownloadKind.TORRENT
            f.told.single().second shouldBe "Added: Some Show S01"
        }

    @Test
    fun aLinkWithoutAFileNameIsNamedByItsHost() =
        hotkeyTest { f ->
            f.clipboard = "https://files.example.com/"

            f.queue.press()

            f.told.single().second shouldBe "Added: files.example.com"
        }

    @Test
    fun theSameLinkTwiceIsAlreadyInTheList() =
        hotkeyTest { f ->
            f.clipboard = "https://files.example.com/a.zip"
            f.queue.press()
            runCurrent()

            f.queue.press()
            runCurrent()

            f.h.engine.downloads.value.size shouldBe 1
            f.told.map { it.second } shouldContainExactly listOf("Added: a.zip", "Already in the list")
        }

    @Test
    fun whatIsNotALinkOrNoTextAtAllIsToldAndAddsNothing() =
        hotkeyTest { f ->
            for (text in listOf("hello world", null, "", "   ", "javascript:alert(1)")) {
                f.clipboard = text
                f.queue.press()
            }
            runCurrent()

            f.h.engine.downloads.value shouldBe emptyList()
            f.heard shouldBe emptyList()
            f.told.map { it.second }.toSet() shouldBe setOf("No link on the clipboard")
            f.told.size shouldBe 5
        }

    @Test
    fun textThatIsNotJustALinkIsNotAddedAsOne() =
        hotkeyTest { f ->
            for (text in listOf(
                "https://a.example/x.zip\nhttps://b.example/y.zip",
                "https://a.example/x.zip is the one I meant, have a look",
                "https://a.example/" + "a".repeat(3000),
                "Notes for later: https://a.example/x.zip",
            )) {
                f.clipboard = text
                f.queue.press()
            }
            runCurrent()

            f.h.engine.downloads.value shouldBe emptyList()
            f.told.map { it.second }.toSet() shouldBe setOf("No link on the clipboard")
        }

    @Test
    fun aTorrentFileOnThisMachineIsAddedByTheUsersOwnPress() =
        hotkeyTest { f ->
            f.clipboard = "/home/me/my show.torrent"

            f.queue.press()
            runCurrent()

            f.heard.single().kind shouldBe DownloadKind.TORRENT
            f.told
                .single()
                .second
                .startsWith("Added: ") shouldBe true
        }

    @Test
    fun anEngineThatCannotAddStillTellsTheUser() =
        hotkeyTest { f ->
            f.clipboard = "https://files.example.com/a.zip"
            f.h.engine.stop()

            f.queue.press()

            f.told
                .single()
                .second
                .startsWith("Could not add: ") shouldBe true
        }

    @Test
    fun aLoginInTheLinkIsNotInTheNotification() =
        hotkeyTest { f ->
            f.clipboard = "ftp://ann:secret@example.com/dir/a.zip"

            f.queue.press()

            f.told.single().second shouldBe "Added: a.zip"
            f.told.toString().contains("secret") shouldBe false
        }

    @Test
    fun aNameWithLineBreaksOrAGreatLengthIsMadeSafe() =
        hotkeyTest { f ->
            f.clipboard = "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335aa7c1367a88a&dn=evil%0Aname%07" + "x".repeat(300)

            f.queue.press()

            val message = f.told.single().second
            message.contains('\n') shouldBe false
            message.contains('\u0007') shouldBe false
            (message.length <= 80) shouldBe true
        }

    @Test
    fun withTheNotificationsSettingOffTheLinkIsStillAddedButNothingIsSaid() =
        hotkeyTest { f ->
            f.enabled = false
            f.clipboard = "https://files.example.com/a.zip"

            f.queue.press()
            runCurrent()

            f.h.engine.downloads.value.size shouldBe 1
            f.told shouldBe emptyList()
        }

    @Test
    fun aNotifierThatFailsDoesNotUndoTheAdd() =
        hotkeyTest { f ->
            f.broken = true
            f.clipboard = "https://files.example.com/a.zip"

            f.queue.press()
            runCurrent()

            f.h.engine.downloads.value.size shouldBe 1
        }
}
