package dev.anydm.engine.os

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** The bridge on a real socket (OSI-01, OSI-02): who may talk to it, and in what form. */
class BridgeServerTest {
    private class FakeBridge : Bridge {
        val added = mutableListOf<Bridge.AddRequest>()
        val confirmed = mutableListOf<String>()

        /** Whether each call to [add] and [confirm] came from a paired browser, in order. */
        val addAuthenticated = mutableListOf<Boolean>()
        val confirmAuthenticated = mutableListOf<Boolean>()
        var addReply: Bridge.AddReply = Bridge.AddReply.Probed("t".repeat(32), "a.zip", 5000, DownloadKind.DIRECT, true, false)
        var confirmReply: Bridge.ConfirmReply = Bridge.ConfirmReply.Added(DownloadId(7))

        override suspend fun add(
            request: Bridge.AddRequest,
            authenticated: Boolean,
        ): Bridge.AddReply {
            added += request
            addAuthenticated += authenticated
            return addReply
        }

        override suspend fun confirm(
            token: String,
            authenticated: Boolean,
        ): Bridge.ConfirmReply {
            confirmed += token
            confirmAuthenticated += authenticated
            return confirmReply
        }
    }

    private val bridge = FakeBridge()
    private var origins = emptySet<String>()

    // Sessions and codes run out by this time, which the tests move by hand.
    private val time = TestTimeSource()
    private val pairing = BridgePairing(InMemoryPairStore(), Clock.System, time)
    private val server = BridgeServer(bridge, { origins }, pairing)
    private val port = server.start()
    private val client = OkHttpClient()
    private val extension = "chrome-extension://abcdefghijklmnop"
    private val json = "application/json".toMediaType()

    @AfterTest
    fun close() = server.close()

    private fun call(
        method: String,
        path: String,
        body: String? = null,
        host: String? = "127.0.0.1:$port",
        origin: String? = extension,
        type: String? = "application/json",
        extra: Map<String, String> = emptyMap(),
    ): Response {
        val request =
            Request
                .Builder()
                .url("http://127.0.0.1:$port$path")
                .method(method, body?.toRequestBody(type?.toMediaType()) ?: if (method == "POST") "".toRequestBody() else null)
                .apply {
                    host?.let { header("Host", it) }
                    origin?.let { header("Origin", it) }
                    extra.forEach { (name, value) -> header(name, value) }
                }.build()
        return client.newCall(request).execute()
    }

    private fun Response.obj(): JsonObject = use { Json.parseToJsonElement(it.body.string()).jsonObject }

    private val addBody =
        """{"url":"https://files.example.com/a.zip","cookies":"sid=abc","headers":{"X-A":"1"},"pageUrl":"https://example.com/dl"}"""

    @Test
    fun anAddAndAConfirmGoThroughAsJson() {
        val added = call("POST", "/add", addBody)
        added.code shouldBe 200
        added.header("Content-Type")!! shouldContain "application/json"
        val reply = added.obj()
        reply.getValue("token").jsonPrimitive.content shouldBe "t".repeat(32)
        reply.getValue("name").jsonPrimitive.content shouldBe "a.zip"
        reply.getValue("size").jsonPrimitive.content shouldBe "5000"
        reply.getValue("kind").jsonPrimitive.content shouldBe "DIRECT"
        reply.getValue("rangesSupported").jsonPrimitive.boolean shouldBe true
        reply.getValue("duplicate").jsonPrimitive.boolean shouldBe false
        bridge.added.single() shouldBe
            Bridge.AddRequest(
                "https://files.example.com/a.zip",
                "sid=abc",
                listOf("X-A" to "1"),
                null,
                "https://example.com/dl",
            )

        val confirmed = call("POST", "/confirm", """{"token":"${"t".repeat(32)}"}""")
        confirmed.code shouldBe 200
        confirmed
            .obj()
            .getValue("id")
            .jsonPrimitive.content shouldBe "7"
        bridge.confirmed.single() shouldBe "t".repeat(32)
    }

    @Test
    fun aPingTellsWhichPortHoldsAnydm() {
        val reply = call("GET", "/ping")
        reply.code shouldBe 200
        reply
            .obj()
            .getValue("app")
            .jsonPrimitive.content shouldBe "anydm"
    }

    @Test
    fun localhostIsAHostToo() {
        call("GET", "/ping", host = "localhost:$port").code shouldBe 200
    }

    @Test
    fun anotherHostIsRefusedBeforeAnythingIsRead() {
        for (host in listOf(
            "evil.example",
            "evil.example:$port",
            "127.0.0.1",
            "127.0.0.1:1",
            "localhost.evil.example:$port",
            "127.0.0.1.evil.example:$port",
        )) {
            call("POST", "/add", addBody, host = host).code shouldBe 403
        }
        bridge.added shouldBe emptyList()
    }

    @Test
    fun anOriginThatIsNotAnExtensionIsRefused() {
        for (origin in listOf(
            "https://evil.example",
            "http://127.0.0.1:$port",
            "null",
            "chrome-extension://",
            "file://",
            "https://chrome-extension",
        )) {
            call("POST", "/add", addBody, origin = origin).code shouldBe 403
        }
        call("POST", "/add", addBody, origin = null).code shouldBe 403
        bridge.added shouldBe emptyList()
    }

    @Test
    fun firefoxExtensionsAreAnExtensionOriginToo() {
        call("POST", "/add", addBody, origin = "moz-extension://5f3c-12ab").code shouldBe 200
    }

    @Test
    fun withTheSettingOnlyThoseExtensionsAreAnswered() {
        origins = setOf("chrome-extension://mine")

        call("POST", "/add", addBody, origin = "chrome-extension://mine").code shouldBe 200
        call("POST", "/add", addBody, origin = "chrome-extension://other").code shouldBe 403
        call("POST", "/add", addBody, origin = "moz-extension://mine").code shouldBe 403
        bridge.added.size shouldBe 1
    }

    @Test
    fun onlyJsonIsTaken() {
        call("POST", "/add", addBody, type = "text/plain").code shouldBe 415
        call("POST", "/add", addBody, type = "application/x-www-form-urlencoded").code shouldBe 415
        call("POST", "/add", addBody, type = null).code shouldBe 415
        call("POST", "/add", addBody, type = "application/json; charset=utf-8").code shouldBe 200
        bridge.added.size shouldBe 1
    }

    @Test
    fun aBodyOver64KbIsRefused() {
        val pad = "a".repeat(64 * 1024)
        call("POST", "/add", """{"url":"https://example.com/a","cookies":"$pad"}""").code shouldBe 413
        bridge.added shouldBe emptyList()
    }

    @Test
    fun aBodyOfExactly64KbIsRead() {
        val body = """{"url":"https://example.com/a","pad":""" + "\"" + "a".repeat(64 * 1024 - 40) + "\"}"
        (body.length <= 64 * 1024) shouldBe true
        call("POST", "/add", body).code shouldBe 200
    }

    @Test
    fun aBodyWithNoLengthIsCappedToo() {
        val pad = "a".repeat(80 * 1024)
        val chunked =
            object : okhttp3.RequestBody() {
                override fun contentType() = json

                override fun writeTo(sink: okio.BufferedSink) {
                    sink.writeUtf8("""{"url":"https://example.com/a","cookies":"$pad"}""")
                }
            }
        val request =
            Request
                .Builder()
                .url("http://127.0.0.1:$port/add")
                .post(chunked)
                .header("Origin", extension)
                .build()
        client.newCall(request).execute().use { it.code shouldBe 413 }
        bridge.added shouldBe emptyList()
    }

    @Test
    fun brokenJsonAndMissingFieldsAreBadRequests() {
        call("POST", "/add", "{not json").code shouldBe 400
        call("POST", "/add", "[]").code shouldBe 400
        call("POST", "/add", """{"cookies":"x"}""").code shouldBe 400
        call("POST", "/add", """{"url":5}""").code shouldBe 400
        call("POST", "/add", """{"url":"https://example.com/a","headers":[1]}""").code shouldBe 400
        call("POST", "/confirm", "{}").code shouldBe 400
        bridge.added shouldBe emptyList()
        bridge.confirmed shouldBe emptyList()
    }

    @Test
    fun aPreflightIsAnsweredForAnExtensionAndAsksNothing() {
        val reply =
            call(
                "OPTIONS",
                "/add",
                origin = extension,
                type = null,
                extra = mapOf("Access-Control-Request-Method" to "POST", "Access-Control-Request-Private-Network" to "true"),
            )

        reply.code shouldBe 204
        reply.header("Access-Control-Allow-Origin") shouldBe extension
        reply.header("Access-Control-Allow-Methods")!! shouldContain "POST"
        reply.header("Access-Control-Allow-Headers")!! shouldContain "Content-Type"
        reply.header("Access-Control-Allow-Private-Network") shouldBe "true"
        reply.header("Vary")!! shouldContain "Origin"
        bridge.added shouldBe emptyList()
    }

    @Test
    fun aPreflightFromAnotherOriginIsRefused() {
        call("OPTIONS", "/add", origin = "https://evil.example", type = null).code shouldBe 403
    }

    @Test
    fun answersCarryTheCorsHeadersOfTheirOriginOnly() {
        val ok = call("POST", "/add", addBody)
        ok.header("Access-Control-Allow-Origin") shouldBe extension
        ok.close()
        val refused = call("POST", "/add", addBody, origin = "https://evil.example")
        refused.header("Access-Control-Allow-Origin") shouldBe null
        refused.close()
    }

    @Test
    fun unknownPathsAndMethodsAreRefusedAsJson() {
        val missing = call("POST", "/nope", addBody)
        missing.code shouldBe 404
        missing.header("Content-Type")!! shouldContain "application/json"
        missing.close()
        call("GET", "/add").code shouldBe 405
        call("DELETE", "/add").code shouldBe 405
    }

    @Test
    fun aRefusalOfTheBridgeBecomesAStatusAndNeverEchoesTheRequest() {
        val cases =
            mapOf(
                Bridge.Problem.BAD_REQUEST to 400,
                Bridge.Problem.NOT_A_LINK to 422,
                Bridge.Problem.UNREADABLE to 502,
                Bridge.Problem.UNAVAILABLE to 503,
                Bridge.Problem.DUPLICATE to 409,
            )
        for ((problem, status) in cases) {
            bridge.addReply = Bridge.AddReply.Refused(problem, "no")
            val reply = call("POST", "/add", addBody)
            reply.code shouldBe status
            val text = reply.obj().toString()
            text shouldContain "no"
            text shouldNotContain "sid=abc"
        }
    }

    @Test
    fun anUnknownTokenIsGone() {
        bridge.confirmReply = Bridge.ConfirmReply.UnknownToken
        call("POST", "/confirm", """{"token":"x"}""").code shouldBe 410
        bridge.confirmReply = Bridge.ConfirmReply.Refused(Bridge.Problem.DUPLICATE, "already in the list")
        call("POST", "/confirm", """{"token":"x"}""").code shouldBe 409
    }

    @Test
    fun itListensOnTheLoopbackOnly() {
        server.address.address.isLoopbackAddress shouldBe true
        server.address.address shouldBe InetAddress.getByName("127.0.0.1")
    }

    // Two ports next to each other that both are free now.
    private fun twoFreePorts(): Int {
        val loopback = InetAddress.getByName("127.0.0.1")
        repeat(50) {
            val first = ServerSocket(0, 1, loopback).use { it.localPort }
            try {
                ServerSocket(first, 1, loopback).use { a -> ServerSocket(first + 1, 1, loopback).use { b -> a.localPort + b.localPort } }
                return first
            } catch (_: IOException) {
                // Taken since: look again.
            }
        }
        error("no two free ports next to each other")
    }

    @Test
    fun aTakenPortMakesItTakeTheNext() {
        val first = twoFreePorts()
        ServerSocket(first, 1, InetAddress.getByName("127.0.0.1")).use {
            BridgeServer(bridge, { origins }, pairing, first..first + 1).use { other ->
                other.start() shouldBe first + 1
            }
        }
    }

    @Test
    fun twoOriginsAreAmbiguousAndRefused() {
        val twoOrigins =
            Request
                .Builder()
                .url("http://127.0.0.1:$port/ping")
                .header("Origin", extension)
                .addHeader("Origin", "https://evil.example")
                .build()
        client.newCall(twoOrigins).execute().use { it.code shouldBe 403 }
    }

    @Test
    fun aRefusedBodyDoesNotCostTheConnection() {
        val big = "a".repeat(80 * 1024)
        call("POST", "/add", """{"url":"https://example.com/a","cookies":"$big"}""").use { it.code shouldBe 413 }

        call("GET", "/ping").use { it.code shouldBe 200 }
    }

    @Test
    fun noFreePortIsAFailureNotASilentOne() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { taken ->
            BridgeServer(bridge, { origins }, pairing, taken.localPort..taken.localPort).use { other ->
                shouldThrow<IOException> { other.start() }
            }
        }
    }

    @Test
    fun aClosedServerAnswersNoMore() {
        server.close()
        shouldThrow<IOException> { call("GET", "/ping") }
    }

    // ---- pairing and signed requests ----

    private val client2 = ExtensionClient(port)

    private fun newCode(): String = runBlocking { pairing.startPairing().code }

    /** [client2], paired and with a session, over the wire. */
    private fun pairedClient(): ExtensionClient {
        client2.pair(newCode()).status shouldBe 200
        client2.hello().status shouldBe 200
        return client2
    }

    private val addBodyNoCookies = """{"url":"https://files.example.com/a.zip"}"""

    @Test
    fun aBrowserPairsAndTheExtensionCanTellTheAppKnewTheCode() {
        val answer = client2.pair(newCode())

        answer.status shouldBe 200
        answer.body.keys shouldBe setOf("id", "nonceA", "proofA")
        answer.header("Content-Type")!! shouldContain "application/json"
        runBlocking { pairing.listed() }.single().apply {
            id shouldBe client2.peer.id
            label shouldBe "Firefox 131 (Linux)"
        }
    }

    @Test
    fun aPairingAnswerNeverHoldsTheSecret() {
        val answer = client2.pair(newCode())

        answer.body.toString() shouldNotContain client2.peer.secret
    }

    @Test
    fun aWrongProofIs401AndLeavesNothingToCollect() {
        newCode()
        val wrong = ExtensionClient(port, PairingPeer(3, port)).pair(WRONG_CODE)

        wrong.status shouldBe 401
        wrong.error shouldBe "wrong_code"
        wrong.body.keys shouldBe setOf("error", "message")
    }

    @Test
    fun noCodeIs409() {
        val answer = ExtensionClient(port, PairingPeer(3, port)).pair(WRONG_CODE)

        answer.status shouldBe 409
        answer.error shouldBe "no_code"
    }

    @Test
    fun tooManyTriesAreAnswered429WithWhenToComeBack() {
        val other = ExtensionClient(port, PairingPeer(3, port))
        repeat(10) {
            newCode()
            other.pair(WRONG_CODE).status shouldBe 401
        }
        newCode()

        val answer = other.pair(WRONG_CODE)

        answer.status shouldBe 429
        answer.error shouldBe "too_many_attempts"
        (answer.header("Retry-After")!!.toInt() in 1..60) shouldBe true
        // Even the right code is not looked at now.
        client2.pair(newCode()).status shouldBe 429
    }

    @Test
    fun triesWithNoCodeWaitingNeverUseUpTheLimit() {
        val other = ExtensionClient(port, PairingPeer(3, port))
        repeat(30) { other.pair(WRONG_CODE).status shouldBe 409 }

        client2.pair(newCode()).status shouldBe 200
    }

    @Test
    fun aServerOnAnotherPortBindsItsOwnPortIntoEveryProof() {
        val other = twoFreePorts()
        BridgeServer(bridge, { origins }, pairing, other..other).use { second ->
            second.start() shouldBe other
            // An extension that dialed this port, and one that dialed the first server's port but arrives here.
            val right = ExtensionClient(other)
            val wrong = ExtensionClient(other, PairingPeer(3, port))

            wrong.pair(newCode()).error shouldBe "wrong_code"

            right.pair(newCode()).status shouldBe 200
            right.hello().status shouldBe 200
            right.signedPost("/add", addBodyNoCookies).status shouldBe 200
            bridge.addAuthenticated shouldBe listOf(true)
            // The same session on the first server is a stranger's: other nonce, other port, no session there.
            ExtensionClient(port, right.peer).signedPost("/add", addBodyNoCookies).error shouldBe "unauthorized"
        }
    }

    @Test
    fun aPairingMadeForAnotherPortIsAWrongCodeEvenWithTheRightCode() {
        // The extension dialed a relay on another port; what the relay passes on is proved for that port.
        val viaRelay = ExtensionClient(port, PairingPeer(3, port + 1))

        val answer = viaRelay.pair(newCode())

        answer.status shouldBe 401
        answer.error shouldBe "wrong_code"
        runBlocking { pairing.listed() } shouldBe emptyList()
    }

    @Test
    fun aPairingRequestFromAnotherOriginIsRefusedAndCostsTheCodeNothing() {
        val code = newCode()

        client2.pair(code, origin = "https://evil.example").status shouldBe 403
        client2.pair(code, origin = null).status shouldBe 403

        client2.pair(code).status shouldBe 200
    }

    @Test
    fun aPairingRequestToAnotherHostIsRefused() {
        val code = newCode()
        val attempt = PairingPeer().attempt(code)
        val body = """{"nonceE":"${attempt.nonceE}","proofE":"${attempt.proofE}"}"""

        client2.post("/pair", body, host = "evil.example").status shouldBe 403
        client2.post("/hello", body, host = "evil.example").status shouldBe 403
    }

    @Test
    fun aPairingRequestThatIsNotOneIs400() {
        newCode()

        for (body in listOf(
            "{}",
            """{"nonceE":5,"proofE":"x"}""",
            """{"nonceE":"zz","proofE":"zz"}""",
            "[]",
            """{"proofE":"${"a".repeat(64)}"}""",
        )) {
            client2.post("/pair", body).status shouldBe 400
        }
        // None of them used up a try or burned the code.
        runBlocking { pairing.listed() } shouldBe emptyList()
    }

    @Test
    fun pairAndHelloTakeOnlyPostWithJson() {
        call("GET", "/pair").code shouldBe 405
        call("GET", "/hello").code shouldBe 405
        call("POST", "/pair", "{}", type = "text/plain").code shouldBe 415
        call("POST", "/hello", "{}", type = "text/plain").code shouldBe 415
    }

    @Test
    fun helloAnswersWithAProofTheExtensionCanCheck() {
        client2.pair(newCode())

        val answer = client2.hello()

        answer.status shouldBe 200
        answer.body.keys shouldBe setOf("nonceA", "proof")
    }

    @Test
    fun helloForAnIdThatIsNotPairedIs401Unpaired() {
        val answer = client2.hello(id = "0".repeat(32))

        answer.status shouldBe 401
        answer.error shouldBe "unpaired"
    }

    @Test
    fun aHelloThatIsNotOneIs400() {
        client2.post("/hello", "{}").status shouldBe 400
        client2.post("/hello", """{"id":"x","nonceE":"y","proof":"z"}""").status shouldBe 400
        client2.post("/hello", """{"id":5}""").status shouldBe 400
    }

    @Test
    fun aHelloWithoutAProofIs400AndStartsNoSession() {
        client2.pair(newCode()).status shouldBe 200
        val hello = client2.peer.startHello()

        val answer = client2.post("/hello", """{"id":"${hello.id}","nonceE":"${hello.nonceE}"}""")

        answer.status shouldBe 400
        client2.signedPost("/add", addBodyNoCookies).error shouldBe "session_expired"
    }

    @Test
    fun aHelloWithAWrongProofIs401UnpairedLikeAnUnknownIdAndStartsNoSession() {
        client2.pair(newCode()).status shouldBe 200
        val hello = client2.peer.startHello()
        val unknown = ExtensionClient(port, PairingPeer(5, port)).hello(id = "0".repeat(32))

        val wrong = client2.post("/hello", """{"id":"${hello.id}","nonceE":"${hello.nonceE}","proof":"${"0".repeat(64)}"}""")

        wrong.status shouldBe 401
        wrong.error shouldBe "unpaired"
        // The same answer for an id that was never paired: nothing tells the two apart.
        wrong.body shouldBe unknown.body
        client2.signedPost("/add", addBodyNoCookies).error shouldBe "session_expired"
    }

    @Test
    fun aHelloMadeForAnotherPortIs401Unpaired() {
        client2.pair(newCode()).status shouldBe 200
        val relayed = client2.peer.startHello()
        val forPortAbove =
            PairingProtocol.helloRequestProof(client2.peer.secret, relayed.id, relayed.nonceE, port + 1)

        val answer = client2.post("/hello", """{"id":"${relayed.id}","nonceE":"${relayed.nonceE}","proof":"$forPortAbove"}""")

        answer.status shouldBe 401
        answer.error shouldBe "unpaired"
    }

    @Test
    fun aHelloIsAnsweredOnlyToAnExtensionOriginTheUserAllows() {
        client2.pair(newCode()).status shouldBe 200
        val hello = client2.peer.startHello()
        val body = """{"id":"${hello.id}","nonceE":"${hello.nonceE}","proof":"${hello.proof}"}"""

        client2.post("/hello", body, origin = "https://evil.example").status shouldBe 403
        client2.post("/hello", body, origin = null).status shouldBe 403
        origins = setOf("chrome-extension://someotherextensionid")
        client2.post("/hello", body).status shouldBe 403
        origins = emptySet()

        client2.post("/hello", body).status shouldBe 200
    }

    @Test
    fun aSignedAddReachesTheBridgeAsFromAPairedBrowserAndAnUnsignedOneDoesNot() {
        val paired = pairedClient()

        paired.signedPost("/add", addBody).status shouldBe 200
        paired.post("/add", addBodyNoCookies).status shouldBe 200

        bridge.addAuthenticated shouldBe listOf(true, false)
    }

    @Test
    fun aSignedConfirmReachesTheBridgeAsFromAPairedBrowser() {
        val paired = pairedClient()
        val body = """{"token":"${"t".repeat(32)}"}"""

        paired.signedPost("/confirm", body).status shouldBe 200
        paired.post("/confirm", body).status shouldBe 200

        bridge.confirmAuthenticated shouldBe listOf(true, false)
    }

    @Test
    fun aBadMacIs401AndNothingReachesTheBridge() {
        val paired = pairedClient()
        for (body in listOf(addBody, addBodyNoCookies)) {
            val headers = paired.signature("/add", body).toMutableMap()
            headers["X-Anydm-Mac"] = "0".repeat(64)

            val answer = paired.post("/add", body, headers)

            answer.status shouldBe 401
            answer.error shouldBe "unauthorized"
        }
        bridge.added shouldBe emptyList()
    }

    @Test
    fun aSignatureOverAnotherBodyOrPathIs401() {
        val paired = pairedClient()
        val signed = paired.signature("/add", addBodyNoCookies, counter = 1)

        paired.post("/add", addBody, signed).status shouldBe 401
        paired.post("/confirm", """{"token":"x"}""", signed).status shouldBe 401
        bridge.added shouldBe emptyList()
        bridge.confirmed shouldBe emptyList()
    }

    @Test
    fun aRepeatedOrLowerCounterIs401Replay() {
        val paired = pairedClient()
        val signed = paired.signature("/add", addBodyNoCookies, counter = 5)
        paired.post("/add", addBodyNoCookies, signed).status shouldBe 200

        val again = paired.post("/add", addBodyNoCookies, signed)
        val lower = paired.signedPost("/add", addBodyNoCookies, counter = 4)

        again.status shouldBe 401
        again.error shouldBe "replay"
        lower.status shouldBe 401
        lower.error shouldBe "replay"
        bridge.added.size shouldBe 1
    }

    @Test
    fun anIdThatIsNotPairedIs401Unpaired() {
        val paired = pairedClient()
        val headers = paired.signature("/add", addBodyNoCookies).toMutableMap()
        headers["X-Anydm-Id"] = "f".repeat(32)

        val answer = paired.post("/add", addBodyNoCookies, headers)

        answer.status shouldBe 401
        answer.error shouldBe "unpaired"
    }

    @Test
    fun aSessionThatRanOutIs401AndTheExtensionSaysHelloAgain() {
        val paired = pairedClient()
        paired.signedPost("/add", addBodyNoCookies).status shouldBe 200

        time += 61.seconds
        val expired = paired.signedPost("/add", addBodyNoCookies)

        expired.status shouldBe 401
        expired.error shouldBe "session_expired"
        paired.hello().status shouldBe 200
        paired.signedPost("/add", addBodyNoCookies).status shouldBe 200
    }

    @Test
    fun aPairedBrowserThatNeverSaidHelloGets401SessionExpired() {
        client2.pair(newCode())

        val answer = client2.signedPost("/add", addBodyNoCookies)

        answer.status shouldBe 401
        answer.error shouldBe "session_expired"
    }

    @Test
    fun partOfTheCredentialsIs401NotAnonymous() {
        val paired = pairedClient()
        val full = paired.signature("/add", addBodyNoCookies)

        for (name in full.keys) {
            paired.post("/add", addBodyNoCookies, full - name).status shouldBe 401
        }
        bridge.added shouldBe emptyList()
    }

    @Test
    fun twoValuesOfACredentialHeaderAreRefused() {
        val paired = pairedClient()
        val full = paired.signature("/add", addBodyNoCookies)
        val request =
            Request
                .Builder()
                .url("http://127.0.0.1:$port/add")
                .post(addBodyNoCookies.toRequestBody(json))
                .header("Origin", extension)
                .apply { full.forEach { (name, value) -> header(name, value) } }
                .addHeader("X-Anydm-Id", full.getValue("X-Anydm-Id"))
                .build()

        client.newCall(request).execute().use { it.code shouldBe 401 }
        bridge.added shouldBe emptyList()
    }

    @Test
    fun aRevokedBrowserGets401UnpairedAtHelloAndAtOnceOnItsSession() {
        val paired = pairedClient()
        paired.signedPost("/add", addBodyNoCookies).status shouldBe 200

        runBlocking { pairing.revoke(paired.peer.id) } shouldBe RevokeResult.REVOKED

        val add = paired.signedPost("/add", addBodyNoCookies)
        add.status shouldBe 401
        add.error shouldBe "unpaired"
        val hello = paired.hello()
        hello.status shouldBe 401
        hello.error shouldBe "unpaired"
        bridge.added.size shouldBe 1
    }

    @Test
    fun theSigningHeadersAreAllowedInThePreflight() {
        val reply = call("OPTIONS", "/add", origin = extension, type = null, extra = mapOf("Access-Control-Request-Method" to "POST"))

        val allowed = reply.header("Access-Control-Allow-Headers")!!.lowercase()
        for (name in listOf("content-type", "x-anydm-id", "x-anydm-counter", "x-anydm-mac")) allowed shouldContain name
        for (path in listOf("/pair", "/hello", "/confirm")) {
            call("OPTIONS", path, origin = extension, type = null).code shouldBe 204
        }
    }

    @Test
    fun aRefusalOfAnAuthenticatedRequestCarriesTheCorsHeadersOfItsOrigin() {
        val paired = pairedClient()
        val headers = paired.signature("/add", addBodyNoCookies).toMutableMap()
        headers["X-Anydm-Mac"] = "0".repeat(64)

        paired.post("/add", addBodyNoCookies, headers).header("Access-Control-Allow-Origin") shouldBe extension
    }

    @Test
    fun theBridgeRefusingCookiesIsA401() {
        bridge.addReply = Bridge.AddReply.Refused(Bridge.Problem.UNAUTHORIZED, "cookies are taken only from a paired browser")

        val answer = ExtensionClient(port).post("/add", addBody)

        answer.status shouldBe 401
        answer.error shouldBe "unauthorized"
    }

    private companion object {
        /** A code that is well formed and not the one anyone waits for. */
        const val WRONG_CODE = "ABCDEFGHJKMNPQRS"
    }
}
