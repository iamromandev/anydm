package dev.anydm.engine.os

import dev.anydm.engine.Engine
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.platform.DesktopFileSystem
import dev.anydm.engine.platform.ShiftedClock
import dev.anydm.engine.protocol.FakeProtocol
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.openInMemoryDatabase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.security.SecureRandom
import kotlin.random.Random
import kotlin.random.asJavaRandom
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TestTimeSource

/**
 * The bridge as the app wires it, on a real socket and a real engine (fake protocol): the server, the pairing the engine
 * owns, and the bridge service together. The pieces are tested apart; this is that they agree.
 */
class BridgePairingEndToEndTest {
    private val folder = Files.createTempDirectory("anydm-bridge-e2e").toFile()
    private val clock = ShiftedClock(Clock.System)
    private val protocol = FakeProtocol()

    // Sessions and codes run out by this time, which a test moves by hand.
    private val time = TestTimeSource()
    private val store = Store(openInMemoryDatabase(), Dispatchers.IO.limitedParallelism(1))

    // The pairings file, which a test can make unreadable (before the first request: what was read once stays in memory).
    private val pairStore = FlakyPairStore(emptyList()).also { it.failLoad = false }
    private val engine =
        Engine(
            store = store,
            fileSystem = DesktopFileSystem(),
            protocols = DownloadKind.entries.associateWith { protocol },
            defaultFolder = Path(folder.absolutePath),
            clock = clock,
            dispatcher = Dispatchers.Default,
            checkpointEvery = 50.milliseconds,
            monotonic = time,
            pairStore = pairStore,
        ).also { runBlocking { it.start() } }
    private val server = BridgeServer(BridgeService(engine, clock, random = Random(11)), { emptySet() }, engine.bridgePairing)
    private val port = server.start()
    private val extension = ExtensionClient(port)

    @AfterTest
    fun close() {
        server.close()
        runBlocking { engine.stop() }
        folder.deleteRecursively()
    }

    private fun pairedExtension(): ExtensionClient {
        extension.pair(runBlocking { engine.startPairing().code }).status shouldBe 200
        extension.hello().status shouldBe 200
        return extension
    }

    private val withCookies = """{"url":"https://files.example.com/a.zip","cookies":"sid=topsecret","pageUrl":"https://example.com/dl"}"""
    private val withoutCookies = """{"url":"https://files.example.com/a.zip","pageUrl":"https://example.com/dl"}"""

    private fun cookiesOf(id: Long): String? = runBlocking { store.downloads.context(DownloadId(id))?.cookies }

    @Test
    fun theEnginesPairingDrawsFromTheSecureSource() {
        randomOf(engine.bridgePairing).asJavaRandom().shouldBeInstanceOf<SecureRandom>()
    }

    @Test
    fun anUnsignedAddWithCookiesIsRefusedAndTheLinkIsNeverAsked() {
        val answer = extension.post("/add", withCookies)

        answer.status shouldBe 401
        answer.error shouldBe "unauthorized"
        answer.body.toString() shouldNotContain "topsecret"
        protocol.probedLinks shouldBe emptyList()
    }

    @Test
    fun aCookieHeaderIsRefusedWhenUnsignedToo() {
        val answer = extension.post("/add", """{"url":"https://files.example.com/a.zip","headers":{"Cookie":"sid=topsecret"}}""")

        answer.status shouldBe 401
        protocol.probedLinks shouldBe emptyList()
    }

    @Test
    fun anUnsignedAddWithoutCookiesAndItsConfirmStillWork() {
        val probed = extension.post("/add", withoutCookies)
        probed.status shouldBe 200

        val confirmed = extension.post("/confirm", """{"token":"${probed.text("token")}"}""")

        confirmed.status shouldBe 200
        cookiesOf(confirmed.text("id").toLong()) shouldBe null
        engine.downloads.value.size shouldBe 1
    }

    @Test
    fun aPairedBrowserAddsWithItsCookiesThroughBothSteps() {
        val paired = pairedExtension()
        val probed = paired.signedPost("/add", withCookies)
        probed.status shouldBe 200

        val confirmed = paired.signedPost("/confirm", """{"token":"${probed.text("token")}"}""")

        confirmed.status shouldBe 200
        cookiesOf(confirmed.text("id").toLong()) shouldBe "sid=topsecret"
    }

    @Test
    fun aTokenOfAPairedBrowserIsGoneForAnUnsignedConfirmAndStaysGoodForTheBrowser() {
        val paired = pairedExtension()
        val token = paired.signedPost("/add", withCookies).text("token")
        val body = """{"token":"$token"}"""

        paired.post("/confirm", body).status shouldBe 410

        paired.signedPost("/confirm", body).status shouldBe 200
    }

    @Test
    fun aForgedMacWithCookiesIsRefusedNotTakenForAnUnsignedRequest() {
        val paired = pairedExtension()
        val headers = paired.signature("/add", withCookies).toMutableMap()
        headers["X-Anydm-Mac"] = "0".repeat(64)

        paired.post("/add", withCookies, headers).status shouldBe 401
        paired.post("/add", withoutCookies, headers).status shouldBe 401
        protocol.probedLinks shouldBe emptyList()
    }

    @Test
    fun aSessionThatRanOutCanNotSendCookies() {
        val paired = pairedExtension()
        time += 61.seconds

        val answer = paired.signedPost("/add", withCookies)

        answer.status shouldBe 401
        answer.error shouldBe "session_expired"
        protocol.probedLinks shouldBe emptyList()
    }

    @Test
    fun revokingFromTheEngineEndsTheBrowserAtOnce() {
        val paired = pairedExtension()
        paired.signedPost("/add", withCookies).status shouldBe 200

        runBlocking { engine.revokeBrowser(paired.peer.id) } shouldBe RevokeResult.REVOKED

        paired.signedPost("/add", withCookies).error shouldBe "unpaired"
        paired.hello().error shouldBe "unpaired"
        runBlocking { engine.listedBrowsers() } shouldBe emptyList()
    }

    // ---- a replayed hello (F4) ----

    @Test
    fun aReplayedHelloIs200WithAFreshNonceAndTheLiveSessionThenAnswers401Unauthorized() {
        val paired = pairedExtension()
        paired.signedPost("/add", withoutCookies).status shouldBe 200
        // The extension's own hello request, as an eavesdropper on the loopback would have copied it.
        val hello = paired.peer.startHello()
        val body =
            buildJsonObject {
                put("id", hello.id)
                put("nonceE", hello.nonceE)
                put("proof", hello.proof)
            }.toString()
        val first = paired.post("/hello", body)
        val replayed = paired.post("/hello", body)

        first.status shouldBe 200
        replayed.status shouldBe 200
        replayed.text("nonceA") shouldNotBe first.text("nonceA")

        // The extension's session is the one the first answer opened; the replay cut it, so its request is refused as not signed
        // right (the app can't tell a cut session from a forgery, and says the same).
        paired.peer.acceptHello(HelloReply.Ok(first.text("nonceA"), first.text("proof"))) shouldBe true
        paired.signedPost("/add", withoutCookies).apply {
            status shouldBe 401
            error shouldBe "unauthorized"
        }
        protocol.probedLinks.size shouldBe 1

        // Saying hello again (a fresh nonceE) is the way back, and the session works.
        paired.hello().status shouldBe 200
        paired.signedPost("/add", withoutCookies).status shouldBe 200
    }

    // ---- a pairings file that cannot be read is not a file with nobody in it (F2) ----

    private val earlier =
        PairRecord(
            id = "1".repeat(32),
            label = "Chrome 130 (macOS)",
            secret = "ab".repeat(32),
            createdAt = Instant.parse("2026-11-01T10:00:00Z"),
            lastUsed = Instant.parse("2026-11-01T10:00:00Z"),
        )

    // The app looks at a pairings file it could not read once a second at most.
    private fun waitForTheRetry() {
        time += 1.seconds
    }

    /** One browser is paired, and the file that says so can't be read, as at the first look of this run. */
    private fun unreadableWithABrowser() {
        pairStore.records = listOf(earlier)
        pairStore.failLoad = true
    }

    private fun helloOf(nonceE: String): String =
        buildJsonObject {
            put("id", earlier.id)
            put("nonceE", nonceE)
            put("proof", PairingProtocol.helloRequestProof(earlier.secret, earlier.id, nonceE, port))
        }.toString()

    private val signedAsEarlier =
        mapOf("X-Anydm-Id" to earlier.id, "X-Anydm-Counter" to "1", "X-Anydm-Mac" to "0".repeat(64))

    @Test
    fun helloWhileThePairingsCannotBeReadIs503StoreUnavailableNotUnpairedAndWorksOnceTheyCanBeRead() {
        unreadableWithABrowser()
        val nonceE = "c".repeat(32)

        val unavailable = extension.post("/hello", helloOf(nonceE))

        unavailable.status shouldBe 503
        unavailable.error shouldBe "store_unavailable"
        unavailable.header("Content-Type")!! shouldContain "application/json"
        unavailable.body.toString() shouldNotContain earlier.secret

        pairStore.failLoad = false
        waitForTheRetry()
        val answer = extension.post("/hello", helloOf(nonceE))
        answer.status shouldBe 200
        answer.text("proof") shouldBe PairingProtocol.helloProof(earlier.secret, nonceE, answer.text("nonceA"), port)
    }

    @Test
    fun helloForAnIdThatIsNotPairedIsAlso503WhileThePairingsCannotBeRead() {
        unreadableWithABrowser()

        extension.hello(id = "0".repeat(32)).apply {
            status shouldBe 503
            error shouldBe "store_unavailable"
        }
    }

    @Test
    fun aSignedAddAndConfirmWhileThePairingsCannotBeReadAre503AndReachNothing() {
        unreadableWithABrowser()

        extension.post("/add", withCookies, signedAsEarlier).apply {
            status shouldBe 503
            error shouldBe "store_unavailable"
        }
        extension.post("/confirm", """{"token":"${"t".repeat(32)}"}""", signedAsEarlier).apply {
            status shouldBe 503
            error shouldBe "store_unavailable"
        }
        protocol.probedLinks shouldBe emptyList()

        // Readable again: the browser is paired and has no session yet, so it is told to say hello, not that it is unpaired.
        pairStore.failLoad = false
        waitForTheRetry()
        extension.post("/add", withCookies, signedAsEarlier).error shouldBe "session_expired"
    }

    @Test
    fun anUnsignedAddStillWorksWhileThePairingsCannotBeRead() {
        unreadableWithABrowser()

        extension.post("/add", withoutCookies).status shouldBe 200
    }

    @Test
    fun theEngineListsUnavailableAndRevokesNothingWhileThePairingsCannotBeRead() {
        unreadableWithABrowser()

        runBlocking { engine.pairedBrowsers() } shouldBe PairedBrowsers.Unavailable
        runBlocking { engine.revokeBrowser(earlier.id) } shouldBe RevokeResult.STORE_UNAVAILABLE
        pairStore.saves shouldBe 0

        pairStore.failLoad = false
        waitForTheRetry()
        runBlocking { engine.listedBrowsers() }.map { it.id } shouldBe listOf(earlier.id)
        runBlocking { engine.revokeBrowser(earlier.id) } shouldBe RevokeResult.REVOKED
        runBlocking { engine.pairedBrowsers() } shouldBe PairedBrowsers.Listed(emptyList())
    }

    @Test
    fun aPairingWhileThePairingsCannotBeReadIs500NotSavedAndTheCodeStillWorksAfter() {
        unreadableWithABrowser()
        val code = runBlocking { engine.startPairing().code }

        extension.pair(code).apply {
            status shouldBe 500
            error shouldBe "not_saved"
        }

        pairStore.failLoad = false
        waitForTheRetry()
        extension.pair(code).status shouldBe 200
        pairStore.records.map { it.id }.toSet() shouldBe setOf(earlier.id, extension.peer.id)
    }
}
