package dev.anydm.engine.os

import dev.anydm.engine.EngineEvent
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.net.AllowanceAction
import dev.anydm.engine.platform.Notifier
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** What the user is told, and what they are not (OSI-09). */
class NotificationRelayTest {
    private val id = DownloadId(1)
    private val reset = Instant.parse("2026-11-01T00:00:00Z")

    private class Fixture(
        test: TestScope,
    ) {
        val events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64)
        val told = mutableListOf<Pair<String, String>>()
        var enabled = true
        var broken = false
        var known = mutableMapOf<DownloadId, NotificationRelay.Described>()
        val notifier =
            Notifier { title, message ->
                if (broken) error("no notification service")
                told += title to message
            }
        val job =
            NotificationRelay(events, { known[it] }, { enabled }, notifier)
                .start(test.backgroundScope)
    }

    private fun relayTest(body: suspend TestScope.(Fixture) -> Unit) =
        runTest(UnconfinedTestDispatcher()) {
            val f = Fixture(this)
            try {
                body(f)
            } finally {
                f.job.cancel()
            }
        }

    @Test
    fun aFinishedDownloadIsToldByItsFileName() =
        relayTest { f ->
            f.events.emit(EngineEvent.Completed(id, listOf(Path("/downloads/a.zip"), Path("/downloads/b.zip"))))

            f.told shouldContainExactly listOf("Download finished" to "a.zip")
        }

    @Test
    fun aFailedDownloadIsToldByItsNameAndWhy() =
        relayTest { f ->
            f.known[id] = NotificationRelay.Described("big.iso", DownloadKind.DIRECT)

            f.events.emit(EngineEvent.Failed(id, ErrorClass.GONE, "HTTP 404 from files.example.com"))

            f.told shouldContainExactly listOf("Download failed" to "big.iso: HTTP 404 from files.example.com")
        }

    @Test
    fun aFailureNeverRepeatsAnAddressWithItsQuery() =
        relayTest { f ->
            f.known[id] = NotificationRelay.Described("big.iso", DownloadKind.DIRECT)

            f.events.emit(EngineEvent.Failed(id, null, "bad link https://user:pw@example.com/a?token=secret&x=1 and more"))

            val message = f.told.single().second
            message shouldNotContain "secret"
            message shouldNotContain "user:pw"
            message shouldNotContain "https://"
            message shouldContain "bad link"
        }

    @Test
    fun aFailureOfADownloadNobodyKnowsIsStillToldWithoutAName() =
        relayTest { f ->
            f.events.emit(EngineEvent.Failed(id, ErrorClass.AUTH, "HTTP 401"))

            f.told shouldContainExactly listOf("Download failed" to "A download: HTTP 401")
        }

    @Test
    fun anAllowanceReachedNamesTheNetworkAndWhatHappens() =
        relayTest { f ->
            f.events.emit(EngineEvent.AllowanceReached("en0", 10_000, 10_000, AllowanceAction.PAUSE, reset))
            f.events.emit(EngineEvent.AllowanceReached("en5", 5_000, 5_000, AllowanceAction.SLOW, reset))

            f.told.map { it.first } shouldContainExactly listOf("Data allowance reached", "Data allowance reached")
            f.told[0].second shouldContain "en0"
            f.told[0].second shouldContain "paused"
            f.told[1].second shouldContain "en5"
            f.told[1].second shouldContain "slowed"
        }

    @Test
    fun eachEventIsOneNotification() =
        relayTest { f ->
            repeat(3) { f.events.emit(EngineEvent.Completed(DownloadId(it + 1L), listOf(Path("/d/$it.bin")))) }

            f.told.size shouldBe 3
        }

    @Test
    fun withTheSettingOffNothingIsToldAndOnAgainItIs() =
        relayTest { f ->
            f.enabled = false
            f.events.emit(EngineEvent.Completed(id, listOf(Path("/d/a.zip"))))
            f.told shouldBe emptyList()

            f.enabled = true
            f.events.emit(EngineEvent.Completed(id, listOf(Path("/d/b.zip"))))
            f.told shouldContainExactly listOf("Download finished" to "b.zip")
        }

    @Test
    fun aNotifierThatFailsDoesNotStopTheNextOnes() =
        relayTest { f ->
            f.broken = true
            f.events.emit(EngineEvent.Completed(id, listOf(Path("/d/a.zip"))))
            f.broken = false
            f.events.emit(EngineEvent.Completed(id, listOf(Path("/d/b.zip"))))

            f.told shouldContainExactly listOf("Download finished" to "b.zip")
        }

    @Test
    fun aRuntimeToolInstallingIsNotNews() =
        relayTest { f ->
            f.known[id] = NotificationRelay.Described("yt-dlp", DownloadKind.TOOL)

            f.events.emit(EngineEvent.Completed(id, listOf(Path("/tools/yt-dlp"))))
            f.events.emit(EngineEvent.Failed(id, ErrorClass.TRANSIENT, "no network"))

            f.told shouldBe emptyList()
        }

    @Test
    fun everythingElseIsNotNews() =
        relayTest { f ->
            f.events.emit(EngineEvent.Added(id))
            f.events.emit(EngineEvent.Removed(id))
            f.events.emit(EngineEvent.NetworkLost)
            f.events.emit(EngineEvent.NetworkRestored)
            f.events.emit(EngineEvent.AllowanceWarning("en0", 8, 10, reset))
            f.events.emit(EngineEvent.CookiesNotSaved)

            f.told shouldBe emptyList()
        }

    @Test
    fun aNameWithLineBreaksOrAGreatLengthIsMadeSafe() =
        relayTest { f ->
            f.events.emit(EngineEvent.Completed(id, listOf(Path("/d/evil\nname\u0007" + "x".repeat(300) + ".zip"))))

            val message = f.told.single().second
            message.contains('\n') shouldBe false
            message.contains('\u0007') shouldBe false
            (message.length <= 120) shouldBe true
        }
}
