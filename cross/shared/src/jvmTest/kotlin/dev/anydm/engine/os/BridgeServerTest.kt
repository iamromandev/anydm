package dev.anydm.engine.os

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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

/** The bridge on a real socket (OSI-01, OSI-02): who may talk to it, and in what form. */
class BridgeServerTest {
    private class FakeBridge : Bridge {
        val added = mutableListOf<Bridge.AddRequest>()
        val confirmed = mutableListOf<String>()
        var addReply: Bridge.AddReply = Bridge.AddReply.Probed("t".repeat(32), "a.zip", 5000, DownloadKind.DIRECT, true, false)
        var confirmReply: Bridge.ConfirmReply = Bridge.ConfirmReply.Added(DownloadId(7))

        override suspend fun add(request: Bridge.AddRequest): Bridge.AddReply {
            added += request
            return addReply
        }

        override suspend fun confirm(token: String): Bridge.ConfirmReply {
            confirmed += token
            return confirmReply
        }
    }

    private val bridge = FakeBridge()
    private var origins = emptySet<String>()
    private val server = BridgeServer(bridge, { origins })
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
            BridgeServer(bridge, { origins }, first..first + 1).use { other ->
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
            BridgeServer(bridge, { origins }, taken.localPort..taken.localPort).use { other ->
                shouldThrow<IOException> { other.start() }
            }
        }
    }

    @Test
    fun aClosedServerAnswersNoMore() {
        server.close()
        shouldThrow<IOException> { call("GET", "/ping") }
    }
}
