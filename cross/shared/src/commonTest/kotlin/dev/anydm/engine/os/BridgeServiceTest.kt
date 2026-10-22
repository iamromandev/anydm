package dev.anydm.engine.os

import dev.anydm.engine.EngineHarness
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.protocol.LinkProbe
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.store.StoreTestBase
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/** The two steps of adding from the browser (OSI-02), on virtual time. */
abstract class BridgeServiceTest : StoreTestBase() {
    private class Fixture(
        val h: EngineHarness,
        val bridge: BridgeService,
    )

    private fun bridgeTest(body: suspend TestScope.(Fixture) -> Unit) =
        runTest {
            val h = EngineHarness(this, newStore())
            h.engine.start()
            try {
                body(Fixture(h, BridgeService(h.engine, h.clock, random = Random(7))))
            } finally {
                h.engine.stop()
            }
        }

    private fun request(
        url: String = "https://files.example.com/a.zip",
        cookies: String? = null,
        headers: List<Pair<String, String>> = emptyList(),
        referrer: String? = null,
        pageUrl: String? = null,
    ) = Bridge.AddRequest(url, cookies, headers, referrer, pageUrl)

    private fun Bridge.AddReply.token() = shouldBeInstanceOf<Bridge.AddReply.Probed>().token

    @Test
    fun addingProbesTheLinkAndQueuesNothing() =
        bridgeTest { f ->
            f.h.protocol.linkProbe = StepResult.Ok(LinkProbe("a.zip", 5000, true))

            val reply = f.bridge.add(request()).shouldBeInstanceOf<Bridge.AddReply.Probed>()

            reply.name shouldBe "a.zip"
            reply.size shouldBe 5000
            reply.kind shouldBe DownloadKind.DIRECT
            reply.rangesSupported shouldBe true
            reply.duplicate shouldBe false
            reply.token.length shouldBe 32
            f.h.engine.downloads.value shouldBe emptyList()
        }

    @Test
    fun confirmingAddsTheDownloadWithWhatWasSent() =
        bridgeTest { f ->
            val token =
                f.bridge
                    .add(request(cookies = "sid=abc", headers = listOf("X-A" to "1"), pageUrl = "https://example.com/dl"))
                    .token()

            val added = f.bridge.confirm(token).shouldBeInstanceOf<Bridge.ConfirmReply.Added>()

            val context =
                f.h.store.downloads
                    .context(added.id)!!
            context.cookies shouldBe "sid=abc"
            context.headers shouldContainExactly listOf("X-A" to "1")
            context.referrer shouldBe "https://example.com/dl"
            f.h.engine.downloads.value
                .single()
                .download.id shouldBe added.id
        }

    @Test
    fun anExplicitReferrerBeatsThePageAddress() =
        bridgeTest { f ->
            val token = f.bridge.add(request(referrer = "https://r.example/x", pageUrl = "https://example.com/dl")).token()

            val added = f.bridge.confirm(token).shouldBeInstanceOf<Bridge.ConfirmReply.Added>()

            f.h.store.downloads
                .context(added.id)!!
                .referrer shouldBe "https://r.example/x"
        }

    @Test
    fun aTokenWorksOnce() =
        bridgeTest { f ->
            val token = f.bridge.add(request()).token()
            f.bridge.confirm(token).shouldBeInstanceOf<Bridge.ConfirmReply.Added>()

            f.bridge.confirm(token) shouldBe Bridge.ConfirmReply.UnknownToken
            f.h.engine.downloads.value.size shouldBe 1
        }

    @Test
    fun aTokenLivesFifteenSeconds() =
        bridgeTest { f ->
            val early = f.bridge.add(request("https://example.com/early.zip")).token()
            val late = f.bridge.add(request("https://example.com/late.zip")).token()

            advanceTimeBy(14.seconds)
            f.bridge.confirm(early).shouldBeInstanceOf<Bridge.ConfirmReply.Added>()
            advanceTimeBy(2.seconds)

            f.bridge.confirm(late) shouldBe Bridge.ConfirmReply.UnknownToken
            f.h.engine.downloads.value.size shouldBe 1
        }

    @Test
    fun aTokenThatWasNeverGivenIsUnknown() =
        bridgeTest { f ->
            f.bridge.confirm("0".repeat(32)) shouldBe Bridge.ConfirmReply.UnknownToken
            f.bridge.confirm("") shouldBe Bridge.ConfirmReply.UnknownToken
        }

    @Test
    fun theSameLinkTwiceGivesTwoTokensAndTheSecondIsADuplicate() =
        bridgeTest { f ->
            val first = f.bridge.add(request()).token()
            val second = f.bridge.add(request()).token()

            (first != second) shouldBe true
            f.bridge.confirm(first).shouldBeInstanceOf<Bridge.ConfirmReply.Added>()
            f.bridge.confirm(second).shouldBeInstanceOf<Bridge.ConfirmReply.Refused>()
            f.h.engine.downloads.value.size shouldBe 1
        }

    @Test
    fun aLinkAlreadyQueuedIsFlaggedWhenProbed() =
        bridgeTest { f ->
            f.bridge.confirm(f.bridge.add(request()).token())

            f.bridge
                .add(request())
                .shouldBeInstanceOf<Bridge.AddReply.Probed>()
                .duplicate shouldBe true
        }

    @Test
    fun whatCannotBeUsedGetsNoToken() =
        bridgeTest { f ->
            f.bridge
                .add(request("hello"))
                .shouldBeInstanceOf<Bridge.AddReply.Refused>()
                .problem shouldBe
                Bridge.Problem.NOT_A_LINK
            f.bridge
                .add(request(headers = listOf("Bad Name" to "x")))
                .shouldBeInstanceOf<Bridge.AddReply.Refused>()
                .problem shouldBe Bridge.Problem.BAD_REQUEST
            f.bridge
                .add(request(cookies = "a=b\r\nX: y"))
                .shouldBeInstanceOf<Bridge.AddReply.Refused>()
                .problem shouldBe Bridge.Problem.BAD_REQUEST
            f.h.protocol.linkProbe = StepResult.Failed(ErrorClass.GONE, "HTTP 404")
            f.bridge
                .add(request())
                .shouldBeInstanceOf<Bridge.AddReply.Refused>()
                .problem shouldBe Bridge.Problem.UNREADABLE
        }

    @Test
    fun loginHeadersAreNotTakenFromTheBrowser() =
        bridgeTest { f ->
            val token =
                f.bridge
                    .add(request(headers = listOf("X-A" to "1", "Authorization" to "Bearer abc", "proxy-authorization" to "Basic x")))
                    .token()

            val added = f.bridge.confirm(token).shouldBeInstanceOf<Bridge.ConfirmReply.Added>()

            f.h.store.downloads
                .context(added.id)!!
                .headers shouldContainExactly listOf("X-A" to "1")
        }

    @Test
    fun cookiesSentAsAHeaderEndUpSealedLikeAnyOther() =
        bridgeTest { f ->
            val token = f.bridge.add(request(headers = listOf("Cookie" to "sid=abc"))).token()

            val added = f.bridge.confirm(token).shouldBeInstanceOf<Bridge.ConfirmReply.Added>()

            val context =
                f.h.store.downloads
                    .context(added.id)!!
            context.cookies shouldBe "sid=abc"
            context.headers shouldBe emptyList()
        }

    @Test
    fun aLinkWithALoginIsAddedWithItsLogin() =
        bridgeTest { f ->
            val token = f.bridge.add(request("ftp://ann:pw@example.com/f.bin")).token()

            val added = f.bridge.confirm(token).shouldBeInstanceOf<Bridge.ConfirmReply.Added>()

            // The engine moves a login to the keychain when it is given a writer; the harness has none, so the URL keeps it.
            f.h.store.downloads
                .mirrors(added.id)
                .single()
                .url shouldBe "ftp://ann:pw@example.com/f.bin"
        }

    @Test
    fun theBridgeTakesNoFilesFromTheDisk() =
        bridgeTest { f ->
            for (link in listOf("file:///home/me/x.torrent", "/home/me/x.torrent", "C:\\Users\\me\\x.torrent")) {
                f.bridge
                    .add(request(link))
                    .shouldBeInstanceOf<Bridge.AddReply.Refused>()
                    .problem shouldBe Bridge.Problem.NOT_A_LINK
            }
            f.h.protocol.probedLinks shouldBe emptyList()
        }

    @Test
    fun blankCookiesAreNoCookies() =
        bridgeTest { f ->
            val added = f.bridge.confirm(f.bridge.add(request(cookies = "   ")).token()).shouldBeInstanceOf<Bridge.ConfirmReply.Added>()

            f.h.store.downloads
                .context(added.id)
                ?.cookies shouldBe null
        }

    @Test
    fun twoConfirmsOfOneTokenAddOnce() =
        bridgeTest { f ->
            val token = f.bridge.add(request()).token()

            val replies = (1..8).map { async { f.bridge.confirm(token) } }.awaitAll()

            replies.count { it is Bridge.ConfirmReply.Added } shouldBe 1
            replies.count { it == Bridge.ConfirmReply.UnknownToken } shouldBe 7
            f.h.engine.downloads.value.size shouldBe 1
        }

    @Test
    fun theRequestNeverPrintsItsCookiesOrHeaderValues() {
        val text = Bridge.AddRequest("https://example.com/a", "sid=topsecret", listOf("X-Token" to "hunter2")).toString()

        text shouldNotContain "topsecret"
        text shouldNotContain "hunter2"
    }

    @Test
    fun onlyTheNewestTokensAreKept() =
        bridgeTest { f ->
            val first = f.bridge.add(request("https://example.com/0.zip")).token()
            repeat(BridgeService.MAX_PENDING) { f.bridge.add(request("https://example.com/${it + 1}.zip")) }

            f.bridge.confirm(first) shouldBe Bridge.ConfirmReply.UnknownToken
        }
}
