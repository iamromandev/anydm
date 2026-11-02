package dev.anydm.engine.os

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The browser extension on a real socket, as a test double: it pairs, says hello and signs requests the way the protocol
 * says, with [peer] doing the arithmetic. What it sends is what the extension must send, header names and encodings
 * included, so a test here also pins the wire format down.
 */
internal class ExtensionClient(
    private val port: Int,
    val peer: PairingPeer = PairingPeer(port = port),
    private val origin: String = CHROME,
    private val userAgent: String = AGENT,
) {
    class Answer(
        val status: Int,
        val body: JsonObject,
        private val headers: Map<String, String>,
    ) {
        fun header(name: String): String? = headers[name.lowercase()]

        val error: String? get() = body["error"]?.jsonPrimitive?.content

        fun text(name: String): String = body.getValue(name).jsonPrimitive.content
    }

    private val client = OkHttpClient()

    fun post(
        path: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        origin: String? = this.origin,
        host: String = "127.0.0.1:$port",
    ): Answer {
        val request =
            Request
                .Builder()
                .url("http://127.0.0.1:$port$path")
                .post(body.toRequestBody("application/json".toMediaType()))
                .header("Host", host)
                .header("User-Agent", userAgent)
                .apply {
                    origin?.let { header("Origin", it) }
                    headers.forEach { (name, value) -> header(name, value) }
                }.build()
        return client.newCall(request).execute().use { response ->
            val text = response.body.string()
            Answer(
                response.code,
                Json.parseToJsonElement(text).jsonObject,
                response.headers.toMultimap().mapValues { it.value.first() },
            )
        }
    }

    private var attempt: PairingPeer.Attempt? = null

    /** A `/pair` request made with [code]; if the app's answer proves it knew the code, the extension keeps the pairing. */
    fun pair(
        code: String,
        origin: String? = this.origin,
    ): Answer {
        val made = peer.attempt(code)
        attempt = made
        val answer =
            post(
                "/pair",
                buildJsonObject {
                    put("nonceE", made.nonceE)
                    put("proofE", made.proofE)
                }.toString(),
                origin = origin,
            )
        if (answer.status == 200) {
            val reply = PairReply.Paired(answer.text("id"), answer.text("nonceA"), answer.text("proofA"))
            check(peer.accept(code, made, reply)) { "the app's proof of the code does not verify" }
        }
        return answer
    }

    /** A `/hello`; if the app's proof of the secret verifies, a session has begun. */
    fun hello(id: String = peer.id): Answer {
        val request = peer.startHello(id)
        val answer =
            post(
                "/hello",
                buildJsonObject {
                    put("id", request.id)
                    put("nonceE", request.nonceE)
                    put("proof", request.proof)
                }.toString(),
            )
        if (answer.status == 200) {
            check(
                peer.acceptHello(HelloReply.Ok(answer.text("nonceA"), answer.text("proof"))),
            ) { "the app's proof of the secret does not verify" }
        }
        return answer
    }

    /** The three headers that sign [body] for [path]. */
    fun signature(
        path: String,
        body: String,
        counter: Long? = null,
    ): Map<String, String> {
        val bytes = body.encodeToByteArray()
        val signed = if (counter == null) peer.sign(path, bytes) else peer.sign(path, bytes, counter)
        return mapOf("X-Anydm-Id" to signed.id, "X-Anydm-Counter" to signed.counter, "X-Anydm-Mac" to signed.mac)
    }

    fun signedPost(
        path: String,
        body: String,
        counter: Long? = null,
    ): Answer = post(path, body, signature(path, body, counter))

    companion object {
        const val CHROME = "chrome-extension://abcdefghijklmnop"
        const val AGENT = "Mozilla/5.0 (X11; Linux x86_64; rv:131.0) Gecko/20100101 Firefox/131.0"
    }
}
