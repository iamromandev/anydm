package dev.anydm.engine.os

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.store.Settings
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The browser extension (#546) and the bridge agree (OSI-04): `ui/apps/extension/contract/bridge.json` holds the bodies the
 * extension sends and the answers it handles, and the extension's own tests read the same file. Here the real server is
 * asked with each body and must parse it as the file says and answer as the file says.
 */
class BridgeContractTest {
    private class FakeBridge : Bridge {
        val added = mutableListOf<Bridge.AddRequest>()
        var addReply: Bridge.AddReply = Bridge.AddReply.Probed("t".repeat(32), "a.zip", 5000, DownloadKind.DIRECT, true, false)
        var confirmReply: Bridge.ConfirmReply = Bridge.ConfirmReply.Added(DownloadId(7))

        override suspend fun add(request: Bridge.AddRequest): Bridge.AddReply {
            added += request
            return addReply
        }

        override suspend fun confirm(token: String): Bridge.ConfirmReply = confirmReply
    }

    private val fixture: JsonObject =
        File(
            "../../ui/apps/extension/contract/bridge.json",
        ).also { assertTrue(it.isFile, "the contract file is missing: ${it.absolutePath}") }
            .readText()
            .let { Json.parseToJsonElement(it).jsonObject }
    private val chrome = fixture.string("chromeOrigin")
    private val firefox = fixture.string("firefoxOrigin")
    private val bridge = FakeBridge()

    // The settings default, as the app wires it.
    private val server = BridgeServer(bridge, { Settings().bridgeOrigins })
    private val port = server.start()
    private val client = OkHttpClient()
    private val json = "application/json".toMediaType()

    @AfterTest
    fun close() = server.close()

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private fun post(
        path: String,
        body: JsonElement,
        origin: String = chrome,
        type: String = "application/json",
    ): Pair<Int, JsonObject> {
        val request =
            Request
                .Builder()
                .url("http://127.0.0.1:$port$path")
                .post(body.toString().toRequestBody(type.toMediaType()))
                .header("Host", "127.0.0.1:$port")
                .header("Origin", origin)
                .build()
        return client.newCall(request).execute().use { it.code to Json.parseToJsonElement(it.body.string()).jsonObject }
    }

    private fun JsonElement.orNull(): String? = if (this is JsonNull) null else jsonPrimitive.content

    private fun requests(): List<JsonObject> = fixture.getValue("requests").jsonArray.map { it.jsonObject }

    private fun replies(): List<JsonObject> = fixture.getValue("replies").jsonArray.map { it.jsonObject }

    @Test
    fun theChromeExtensionIsTheOneTheAppTrustsByDefault() {
        chrome shouldBe Settings.CHROME_EXTENSION_ORIGIN
        Settings().bridgeOrigins shouldBe setOf(chrome)
    }

    @Test
    fun everyBodyTheExtensionSendsIsReadAsTheFileSays() {
        requests().forEach { case ->
            bridge.added.clear()
            val (status, _) = post("/add", case.getValue("body"))

            status shouldBe 200
            val parsed = case.getValue("parsed").jsonObject
            val headers =
                parsed.getValue("headers").jsonArray.map { pair ->
                    val (name, value) = pair.jsonArray
                    name.jsonPrimitive.content to value.jsonPrimitive.content
                }
            bridge.added.single() shouldBe
                Bridge.AddRequest(
                    url = parsed.string("url"),
                    cookies = parsed.getValue("cookies").orNull(),
                    headers = headers,
                    referrer = parsed.getValue("referrer").orNull(),
                    pageUrl = parsed.getValue("pageUrl").orNull(),
                )
        }
    }

    private fun bridgeReply(case: JsonObject) {
        val body = case.getValue("body").jsonObject
        val message = body["message"]?.jsonPrimitive?.content ?: ""
        when (case.string("name")) {
            "probed" -> {
                bridge.addReply =
                    Bridge.AddReply.Probed("0123456789abcdef0123456789abcdef", "a.zip", 5000, DownloadKind.DIRECT, true, false)
            }

            "probed-unknown" -> {
                bridge.addReply =
                    Bridge.AddReply.Probed("0123456789abcdef0123456789abcdef", null, null, DownloadKind.DIRECT, false, true)
            }

            "add-bad-request" -> {
                bridge.addReply = Bridge.AddReply.Refused(Bridge.Problem.BAD_REQUEST, message)
            }

            "add-not-a-link" -> {
                bridge.addReply = Bridge.AddReply.Refused(Bridge.Problem.NOT_A_LINK, message)
            }

            "add-unreadable" -> {
                bridge.addReply = Bridge.AddReply.Refused(Bridge.Problem.UNREADABLE, message)
            }

            "add-unavailable" -> {
                bridge.addReply = Bridge.AddReply.Refused(Bridge.Problem.UNAVAILABLE, message)
            }

            "added" -> {
                bridge.confirmReply =
                    Bridge.ConfirmReply.Added(
                        DownloadId(
                            body
                                .getValue("id")
                                .jsonPrimitive.int
                                .toLong(),
                        ),
                    )
            }

            "confirm-duplicate" -> {
                bridge.confirmReply = Bridge.ConfirmReply.Refused(Bridge.Problem.DUPLICATE, message)
            }

            "confirm-unavailable" -> {
                bridge.confirmReply = Bridge.ConfirmReply.Refused(Bridge.Problem.UNAVAILABLE, message)
            }

            "unknown-token" -> {
                bridge.confirmReply = Bridge.ConfirmReply.UnknownToken
            }

            else -> {
                error("the file has a reply this test does not know: ${case.string("name")}")
            }
        }
    }

    @Test
    fun everyAnswerTheExtensionHandlesIsWhatTheServerSends() {
        val add = requests().first().getValue("body")
        replies().forEach { case ->
            bridgeReply(case)
            val path = case.string("path")
            val (status, body) = post(path, if (path == "/add") add else Json.parseToJsonElement("""{"token":"x"}"""))

            status shouldBe case.getValue("status").jsonPrimitive.int
            body shouldBe case.getValue("body").jsonObject
        }
    }

    @Test
    fun aBrowserThatIsNotPairedIsRefusedAsTheFileSays() {
        val refusal =
            fixture
                .getValue("refusals")
                .jsonArray
                .map { it.jsonObject }
                .first { it.string("name") == "origin-not-allowed" }
        val (status, body) = post("/add", requests().first().getValue("body"), origin = firefox)

        status shouldBe refusal.getValue("status").jsonPrimitive.int
        body shouldBe refusal.getValue("body").jsonObject
        bridge.added.isEmpty() shouldBe true
    }

    @Test
    fun aBodyThatIsNotJsonIsRefusedAsTheFileSays() {
        val refusal =
            fixture
                .getValue("refusals")
                .jsonArray
                .map { it.jsonObject }
                .first { it.string("name") == "not-json" }
        val (status, body) = post("/add", requests().first().getValue("body"), type = "text/plain")

        status shouldBe refusal.getValue("status").jsonPrimitive.int
        body shouldBe refusal.getValue("body").jsonObject
    }

    @Test
    fun theFirefoxOriginIsAnsweredOnceTheUserAddsIt() {
        val paired = BridgeServer(bridge, { setOf(chrome, firefox) })
        val pairedPort = paired.start()
        try {
            val request =
                Request
                    .Builder()
                    .url("http://127.0.0.1:$pairedPort/ping")
                    .header("Host", "127.0.0.1:$pairedPort")
                    .header("Origin", firefox)
                    .build()
            val answer = client.newCall(request).execute().use { it.code to Json.parseToJsonElement(it.body.string()).jsonObject }

            answer.first shouldBe 200
            answer.second shouldBe fixture.getValue("ping").jsonObject
        } finally {
            paired.close()
        }
    }
}
