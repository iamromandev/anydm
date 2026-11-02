package dev.anydm.engine.os

import dev.anydm.engine.Engine
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.platform.DesktopFileSystem
import dev.anydm.engine.platform.ShiftedClock
import dev.anydm.engine.protocol.FakeProtocol
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.openInMemoryDatabase
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * The extension and the server agree on the HTTP of pairing: `ui/apps/extension/contract/pairing-http.json` lists, for `/pair`,
 * `/hello` and a signed `/add` and `/confirm`, the request (path, headers, body fields), the status and the error code and
 * message of each answer, and the extension's own tests put the same cases to its model of the app. Here the real server, on a
 * real socket with a real engine, is asked with each case from the state the case says (`given`) and must answer as the file says.
 */
class PairingHttpContractTest {
    private val fixture: JsonObject =
        File("../../ui/apps/extension/contract/pairing-http.json")
            .also { assertTrue(it.isFile, "the contract file is missing: ${it.absolutePath}") }
            .readText()
            .let { Json.parseToJsonElement(it).jsonObject }
    private val answers = fixture.getValue("answers").jsonObject
    private val requests = fixture.getValue("requests").jsonObject
    private val cases = fixture.getValue("cases").jsonArray.map { it.jsonObject }

    private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content

    private fun JsonObject.strings(name: String) = getValue(name).jsonArray.map { it.jsonPrimitive.content }

    /** An app, a server on a socket and an extension that talks to it, for one case. */
    private class World : AutoCloseable {
        private val folder = Files.createTempDirectory("anydm-pairing-http").toFile()
        val time = TestTimeSource()
        val pairStore = FlakyPairStore(emptyList()).also { it.failLoad = false }
        private val clock = ShiftedClock(Clock.System)
        private val engine =
            Engine(
                store = Store(openInMemoryDatabase(), Dispatchers.IO.limitedParallelism(1)),
                fileSystem = DesktopFileSystem(),
                protocols = DownloadKind.entries.associateWith { FakeProtocol() },
                defaultFolder = Path(folder.absolutePath),
                clock = clock,
                dispatcher = Dispatchers.Default,
                checkpointEvery = 50.milliseconds,
                monotonic = time,
                pairStore = pairStore,
            ).also { runBlocking { it.start() } }
        private val server = BridgeServer(BridgeService(engine, clock, random = Random(11)), { emptySet() }, engine.bridgePairing)
        val port = server.start()
        val extension = ExtensionClient(port)
        var code: String? = null
        var token = ""

        fun startPairing() {
            code = runBlocking { engine.startPairing().code }
        }

        override fun close() {
            server.close()
            runBlocking { engine.stop() }
            folder.deleteRecursively()
        }
    }

    private val plainAdd = """{"url":"https://files.example.com/a.zip"}"""
    private val wrongPair = """{"nonceE":"${"00112233445566778899aabbccddeeff"}","proofE":"${"0".repeat(64)}"}"""

    private fun World.ensurePaired() {
        if (extension.peer.id.isNotEmpty()) return
        startPairing()
        extension.pair(code!!).status shouldBe 200
    }

    private fun World.ensureSession() {
        ensurePaired()
        extension.hello().status shouldBe 200
    }

    private fun World.arrange(given: List<String>) {
        for (step in given) {
            when (step) {
                "code-waiting" -> {
                    startPairing()
                }

                "paired" -> {
                    ensurePaired()
                }

                "session" -> {
                    ensureSession()
                }

                "signed-add-done" -> {
                    ensureSession()
                    token = extension.signedPost("/add", plainAdd).text("token")
                }

                "session-ran-out" -> {
                    time += 61.seconds
                }

                // Before anything was read, so the file can't be read at the first look, as at the start of a run.
                "store-unreadable", "save-fails" -> {
                    pairStore.failLoad = true
                }

                "throttled" -> {
                    // A wrong try burns the code after five, so a new code is started now and then: ten tries in a minute.
                    repeat(10) {
                        if (it % 4 == 0) startPairing()
                        extension.post("/pair", wrongPair)
                    }
                }

                else -> {
                    error("the file has a state this test does not know: $step")
                }
            }
        }
    }

    // A value of the case that starts with `$` is made here, with the protocol.
    private fun World.placeholder(
        name: String,
        body: JsonObject,
    ): String =
        when (name) {
            "\$id" -> {
                extension.peer.id
            }

            "\$token" -> {
                token
            }

            "\$proofE" -> {
                PairingProtocol.pairProof(code!!, body.text("nonceE"), port)
            }

            "\$helloRequestProof" -> {
                PairingProtocol.helloRequestProof(extension.peer.secret, extension.peer.id, body.text("nonceE"), port)
            }

            else -> {
                error("the file has a placeholder this test does not know: $name")
            }
        }

    private fun World.resolved(body: JsonElement): JsonElement =
        if (body is JsonObject) {
            JsonObject(
                body.mapValues { (_, value) ->
                    if (value is JsonPrimitive && value.isString && value.content.startsWith("\$")) {
                        JsonPrimitive(placeholder(value.content, body))
                    } else {
                        value
                    }
                },
            )
        } else {
            body
        }

    private fun World.send(request: JsonObject): ExtensionClient.Answer {
        val path = request.text("path")
        val text = resolved(request.getValue("body")).toString()
        val headers =
            request["headers"]?.jsonObject?.mapValues { (name, value) ->
                when (val given = value.jsonPrimitive.content) {
                    "\$id" -> {
                        extension.peer.id
                    }

                    "\$mac" -> {
                        extension.peer
                            .sign(
                                path,
                                text.encodeToByteArray(),
                                request
                                    .getValue("headers")
                                    .jsonObject
                                    .text("X-Anydm-Counter")
                                    .toLong(),
                            ).mac
                    }

                    else -> {
                        given
                    }
                }
            } ?: emptyMap()
        return extension.post(path, text, headers)
    }

    private fun typeMatches(
        type: String,
        value: JsonElement,
    ): Boolean {
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        return when (type) {
            "hex32" -> text != null && Regex("[0-9a-f]{32}").matches(text)
            "hex64" -> text != null && Regex("[0-9a-f]{64}").matches(text)
            "int" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
            "string" -> text != null
            "bool" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
            "string-or-null" -> value is JsonNull || text != null
            "int-or-null" -> value is JsonNull || (value is JsonPrimitive && !value.isString && value.longOrNull != null)
            else -> error("the file has a type this test does not know: $type")
        }
    }

    private fun check(
        answer: ExtensionClient.Answer,
        expected: JsonObject,
    ) {
        val body = expected["body"]?.jsonObject ?: JsonObject(emptyMap())
        val shape = expected["shape"]?.jsonObject ?: JsonObject(emptyMap())
        answer.status shouldBe expected.getValue("status").jsonPrimitive.int
        answer.body.keys shouldBe body.keys + shape.keys
        body.forEach { (name, value) -> answer.body.getValue(name) shouldBe value }
        shape.forEach { (name, type) -> typeMatches(type.jsonPrimitive.content, answer.body.getValue(name)) shouldBe true }
        expected["headers"]?.jsonObject?.forEach { (name, type) ->
            typeMatches(type.jsonPrimitive.content, JsonPrimitive(answer.header(name)?.toLongOrNull())) shouldBe true
        }
    }

    @Test
    fun everyCaseIsAnsweredByTheServerAsTheFileSays() {
        cases.forEach { case ->
            withClue(case.text("name")) {
                World().use { world ->
                    world.arrange(case.strings("given"))
                    check(world.send(case.getValue("request").jsonObject), answers.getValue(case.text("answer")).jsonObject)
                }
            }
        }
    }

    @Test
    fun theFilesRequestsAreTheOnesItSaysTheAppReads() {
        cases
            .filter {
                answers
                    .getValue(it.text("answer"))
                    .jsonObject
                    .getValue("status")
                    .jsonPrimitive.int == 200
            }.forEach { case ->
                val request = case.getValue("request").jsonObject
                val spec = requests.getValue(request.text("path")).jsonObject
                val fields = spec.strings("bodyFields")
                val sent = request.getValue("body").jsonObject.keys
                withClue(case.text("name")) {
                    if (request.text("path") ==
                        "/add"
                    ) {
                        assertTrue(sent.all { it in fields } && "url" in sent)
                    } else {
                        sent shouldBe fields.toSet()
                    }
                    (request["headers"]?.jsonObject?.keys ?: emptySet<String>()) shouldBe spec.strings("headers").toSet()
                }
            }
    }

    @Test
    fun everyAnswerOfTheFileIsUsedAndEveryCaseNamesOne() {
        cases.map { it.text("answer") }.toSet() shouldBe answers.keys
        cases.flatMap { it.strings("given") }.forEach { assertTrue(it in fixture.getValue("given").jsonObject, "unknown state $it") }
    }
}
