package dev.anydm.engine.os

import dev.anydm.engine.core.DownloadKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import java.io.File
import java.io.IOException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * The pairing protocol's proofs against `ui/apps/extension/contract/pairing-vectors.json`, whose expected values were made
 * by an independent HMAC (not this code). The extension's tests read the same file, so the two sides cannot drift apart.
 */
class PairingVectorsTest {
    private val vectors: JsonObject =
        File("../../ui/apps/extension/contract/pairing-vectors.json")
            .also { assertTrue(it.isFile, "the vectors file is missing: ${it.absolutePath}") }
            .readText()
            .let { Json.parseToJsonElement(it).jsonObject }

    private fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content

    private fun JsonObject.number(name: String) = getValue(name).jsonPrimitive.int

    private val pair = vectors.getValue("pair").jsonObject
    private val hello = vectors.getValue("hello").jsonObject
    private val otherPort = vectors.getValue("otherPort").jsonObject
    private val requests = vectors.getValue("requests").jsonArray.map { it.jsonObject }

    @Test
    fun theExtensionsProofOfTheCodeIsTheFilesProofE() {
        PairingProtocol.pairProof(pair.string("code"), pair.string("nonceE"), pair.number("port")) shouldBe pair.string("proofE")
    }

    @Test
    fun theAppsProofOfTheCodeIsTheFilesProofA() {
        PairingProtocol.appProof(
            pair.string("code"),
            pair.string("nonceE"),
            pair.string("nonceA"),
            pair.number("port"),
        ) shouldBe pair.string("proofA")
    }

    @Test
    fun theSecretIsTheFilesSecretAndDoesNotNeedThePort() {
        PairingProtocol.secret(pair.string("code"), pair.string("nonceE"), pair.string("nonceA")) shouldBe pair.string("secret")
    }

    @Test
    fun theExtensionsHelloProofIsTheFilesRequestProof() {
        PairingProtocol.helloRequestProof(
            hello.string("secret"),
            hello.string("id"),
            hello.string("nonceE"),
            hello.number("port"),
        ) shouldBe hello.string("requestProof")
    }

    @Test
    fun theAppsHelloProofIsTheFilesProof() {
        PairingProtocol.helloProof(
            hello.string("secret"),
            hello.string("nonceE"),
            hello.string("nonceA"),
            hello.number("port"),
        ) shouldBe hello.string("proof")
    }

    @Test
    fun everyRequestMacIsTheFilesMac() {
        requests.forEach { case ->
            val body = case.string("body").encodeToByteArray()

            PairingProtocol.bodyHash(body) shouldBe case.string("bodySha256")
            PairingProtocol.requestMac(
                hello.string("secret"),
                case.string("nonceA"),
                case.string("counter").toLong(),
                case.string("path"),
                body,
                case.number("port"),
            ) shouldBe case.string("mac")
        }
    }

    @Test
    fun theFilesMessagesAreTheOnesWeSign() {
        // The text signed, written out in the file, is what the formulas make; a reader of the file can rely on it.
        val nonceE = pair.string("nonceE")
        val nonceA = pair.string("nonceA")
        val port = pair.number("port")
        val messages = pair.getValue("messages").jsonObject
        messages.string("proofE") shouldBe "anydm-pair-1|$nonceE|$port"
        messages.string("proofA") shouldBe "anydm-app-1|$nonceE|$nonceA|$port"
        messages.string("secret") shouldBe "anydm-secret-1|$nonceE|$nonceA"
        hello.string("requestMessage") shouldBe "anydm-hello-req-1|${hello.string("id")}|${hello.string("nonceE")}|${hello.number("port")}"
        hello.string("message") shouldBe
            "anydm-hello-1|${hello.string("nonceE")}|${hello.string("nonceA")}|${hello.number("port")}"
        requests.forEach { case ->
            case.string("message") shouldBe
                "anydm-req-1|${case.string("nonceA")}|${case.string("counter")}|${case.string("path")}|${case.string("bodySha256")}" +
                "|${case.number("port")}"
        }
    }

    @Test
    fun anotherPortChangesEveryProofButNotTheSecret() {
        val code = pair.string("code")
        val nonceE = pair.string("nonceE")
        val nonceA = pair.string("nonceA")
        val port = otherPort.number("port")
        port shouldNotBe pair.number("port")

        PairingProtocol.pairProof(code, nonceE, port) shouldBe otherPort.string("proofE")
        PairingProtocol.appProof(code, nonceE, nonceA, port) shouldBe otherPort.string("proofA")
        PairingProtocol.secret(code, nonceE, nonceA) shouldBe otherPort.string("secret")
        otherPort.string("secret") shouldBe pair.string("secret")
        PairingProtocol.helloRequestProof(
            hello.string("secret"),
            hello.string("id"),
            hello.string("nonceE"),
            port,
        ) shouldBe otherPort.string("helloRequestProof")
        PairingProtocol.helloProof(
            hello.string("secret"),
            hello.string("nonceE"),
            hello.string("nonceA"),
            port,
        ) shouldBe otherPort.string("helloProof")
        val first = requests.first()
        PairingProtocol.requestMac(
            hello.string("secret"),
            first.string("nonceA"),
            first.string("counter").toLong(),
            first.string("path"),
            first.string("body").encodeToByteArray(),
            port,
        ) shouldBe otherPort.string("mac")
        // And none of them is the proof for the first port.
        listOf("proofE", "proofA").forEach { otherPort.string(it) shouldNotBe pair.string(it) }
        otherPort.string("helloRequestProof") shouldNotBe hello.string("requestProof")
        otherPort.string("helloProof") shouldNotBe hello.string("proof")
        otherPort.string("mac") shouldNotBe first.string("mac")
    }

    @Test
    fun theAlphabetIsCrockfordsAndCodesNormaliseAsTheFileSays() {
        PairingProtocol.ALPHABET shouldBe vectors.string("alphabet")
        vectors.getValue("codeNormalisation").jsonArray.map { it.jsonObject }.forEach { case ->
            val canonical = case.getValue("canonical").let { if (it is JsonNull) null else it.jsonPrimitive.content }

            PairingProtocol.normaliseCode(case.string("input")) shouldBe canonical
        }
    }

    @Test
    fun theFilesCodeIsOfTheLengthTheAppMakes() {
        pair.string("code").length shouldBe PairingProtocol.CODE_LENGTH
        PairingProtocol.CODE_LENGTH shouldBe 16
        PairingProtocol.normaliseCode(pair.string("code")) shouldBe pair.string("code")
    }

    @Test
    fun aCodeIsShownInGroupsOfFour() {
        PairingProtocol.grouped(pair.string("code")) shouldBe "7K3M-9QX2-VBHT-4N6R"
    }

    @Test
    fun aRequestMacIsOnlyForAddAndConfirm() {
        val secret = hello.string("secret")
        val nonceA = hello.string("nonceA")

        for (path in listOf("/ping", "/pair", "/hello", "/add?x=1", "/ADD", "add", "")) {
            shouldThrow<IllegalArgumentException> {
                PairingProtocol.requestMac(secret, nonceA, 1, path, ByteArray(0), 18400)
            }
        }
    }

    @Test
    fun aPortIsAPortNumber() {
        val code = pair.string("code")
        val nonceE = pair.string("nonceE")

        for (port in listOf(0, -1, 65536, 100_000)) {
            shouldThrow<IllegalArgumentException> { PairingProtocol.pairProof(code, nonceE, port) }
        }
        PairingProtocol.pairProof(code, nonceE, 1)
        PairingProtocol.pairProof(code, nonceE, 65535)
    }

    // ---- through the app's own pairing, with the file's inputs as its randomness (S3) ----

    // What BridgePairing draws: the code as 16 indexes into the alphabet, then 16-byte nonces and ids, in this order.
    private class ScriptedRandom(
        indexes: List<Int>,
        blobs: List<ByteArray>,
    ) : Random() {
        private val indexes = ArrayDeque(indexes)
        private val blobs = ArrayDeque(blobs)

        override fun nextBits(bitCount: Int): Int = error("the code is drawn with nextInt(32) and nonces with nextBytes(16)")

        override fun nextInt(until: Int): Int {
            check(until == PairingProtocol.ALPHABET.length) { "a code character is one of ${PairingProtocol.ALPHABET.length}" }
            return indexes.removeFirst()
        }

        override fun nextBytes(size: Int): ByteArray {
            check(size == 16) { "nonces and ids are 16 bytes" }
            return blobs.removeFirst()
        }
    }

    private fun scripted(): ScriptedRandom =
        ScriptedRandom(
            pair.string("code").map { PairingProtocol.ALPHABET.indexOf(it) },
            // The order the app draws in: nonceA and id when pairing, then nonceA of the session.
            listOf(pair.string("nonceA"), pair.string("id"), hello.string("nonceA")).map { checkNotNull(it.hexToBytes()) },
        )

    private class Greeted(
        val paired: PairReply.Paired,
        val hello: HelloReply.Ok,
    )

    // Pairs with the file's code, nonceE and proofE, then says hello with the file's nonceE and request proof.
    private suspend fun pairedAndGreeted(pairing: BridgePairing): Greeted {
        pairing.startPairing().code shouldBe pair.string("code")
        val paired =
            pairing
                .pair(ORIGIN, AGENT, pair.string("nonceE"), pair.string("proofE"), pair.number("port"))
                .shouldBeInstanceOf<PairReply.Paired>()
        val greeted =
            pairing
                .hello(hello.string("id"), hello.string("nonceE"), hello.string("requestProof"), hello.number("port"))
                .shouldBeInstanceOf<HelloReply.Ok>()
        return Greeted(paired, greeted)
    }

    @Test
    fun theAppMakesTheFilesPairingProofAndSecretWhenItsRandomnessIsTheFilesInputs() {
        runBlocking {
            val store = InMemoryPairStore()
            val pairing = BridgePairing(store, Clock.System, random = scripted())

            val paired = pairedAndGreeted(pairing).paired

            paired.id shouldBe pair.string("id")
            paired.nonceA shouldBe pair.string("nonceA")
            paired.proofA shouldBe pair.string("proofA")
            store.load().single().secret shouldBe pair.string("secret")
            hello.string("secret") shouldBe pair.string("secret")
            hello.string("id") shouldBe pair.string("id")
        }
    }

    @Test
    fun theAppMakesTheFilesHelloProofAndAcceptsTheFilesRequestMacs() {
        runBlocking {
            val pairing = BridgePairing(InMemoryPairStore(), Clock.System, random = scripted())

            val greeted = pairedAndGreeted(pairing).hello

            greeted.nonceA shouldBe hello.string("nonceA")
            greeted.proof shouldBe hello.string("proof")
            // A request is accepted only if the MAC the app makes is the file's, so each one that passes proves it.
            requests.forEach { case ->
                pairing.authenticate(
                    hello.string("id"),
                    case.string("counter"),
                    case.string("mac"),
                    case.string("path"),
                    case.string("body").encodeToByteArray(),
                    case.number("port"),
                ) shouldBe AuthReply.Ok(hello.string("id"))
            }
        }
    }

    // ---- the same MAC over a socket, with a body that is not ASCII ----

    private class RecordingBridge : Bridge {
        val added = mutableListOf<Bridge.AddRequest>()
        val addAuthenticated = mutableListOf<Boolean>()

        override suspend fun add(
            request: Bridge.AddRequest,
            authenticated: Boolean,
        ): Bridge.AddReply {
            added += request
            addAuthenticated += authenticated
            return Bridge.AddReply.Probed("t".repeat(32), "a.zip", 5000, DownloadKind.DIRECT, true, false)
        }

        override suspend fun confirm(
            token: String,
            authenticated: Boolean,
        ): Bridge.ConfirmReply = Bridge.ConfirmReply.UnknownToken
    }

    private fun post(
        port: Int,
        body: String,
        counter: String,
        mac: String,
    ): Int =
        OkHttpClient()
            .newCall(
                Request
                    .Builder()
                    .url("http://127.0.0.1:$port/add")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .header("Origin", ORIGIN)
                    .header("X-Anydm-Id", hello.string("id"))
                    .header("X-Anydm-Counter", counter)
                    .header("X-Anydm-Mac", mac)
                    .build(),
            ).execute()
            .use { it.code }

    @Test
    fun aNonAsciiBodyReachesTheBridgeSignedWithTheFilesMacAndAChangedOneDoesNot() {
        val port = pair.number("port")
        val bridge = RecordingBridge()
        val pairing = BridgePairing(InMemoryPairStore(), Clock.System, random = scripted())
        runBlocking { pairedAndGreeted(pairing) }
        val server = BridgeServer(bridge, { emptySet() }, pairing, port..port)
        // The file's port is a fixed number; if something else holds it, there is nothing to test against.
        try {
            server.start()
        } catch (_: IOException) {
            assumeTrue("port $port is taken", false)
        }
        server.use {
            val case = requests.first { it.string("name") == "non-ascii body" }
            val body = case.string("body")
            (body.length < body.encodeToByteArray().size) shouldBe true

            // The MAC over the UTF-8 bytes, as the file has it.
            post(port, body, case.string("counter"), case.string("mac")) shouldBe 200
            bridge.added.single().url shouldBe "https://example.com/üé"
            bridge.addAuthenticated shouldBe listOf(true)

            // The same MAC for another body, and the other port's MAC for this one, are refused and reach nothing.
            post(port, body.replace("üé", "ue"), "4", case.string("mac")) shouldBe 401
            post(port, body, "5", otherPort.string("mac")) shouldBe 401
            bridge.added.size shouldBe 1
        }
    }

    private companion object {
        const val ORIGIN = "chrome-extension://abcdefghijklmnop"
        const val AGENT = "Mozilla/5.0 (X11; Linux x86_64; rv:131.0) Gecko/20100101 Firefox/131.0"
    }
}
