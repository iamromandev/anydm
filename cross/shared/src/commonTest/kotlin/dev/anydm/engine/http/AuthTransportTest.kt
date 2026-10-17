package dev.anydm.engine.http

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeUnique
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test

class AuthTransportTest {
    private val user = Credentials("u", "p")
    private val store = InMemoryCredentials()
    private val bodies = mutableListOf<BytesBody>()

    private fun HttpRequest.authorization() = headers.firstOrNull { it.first.equals("Authorization", ignoreCase = true) }?.second

    private fun tracked() = bytesBody().also { bodies += it }

    private fun auth(transport: HttpTransport) = AuthTransport(transport, store, Random(1))

    /** Basic for u:p; a 401 answers from [finalUrl]. */
    private fun basicServer(finalUrl: (HttpRequest) -> String = { it.url }): suspend (HttpRequest) -> HttpResponse =
        { r ->
            if (r.authorization() == "Basic dTpw") {
                response(200, finalUrl = finalUrl(r))
            } else {
                response(401, "WWW-Authenticate" to "Basic realm=\"r\"", finalUrl = finalUrl(r), body = tracked())
            }
        }

    /** Digest SHA-256 for [valid]; an answer with an older nonce is told it's stale. [rotate]: a new nonce after the first 401. */
    private inner class DigestServer(
        val valid: Credentials = user,
        var rotate: Boolean = false,
    ) {
        var nonce = "n1"

        fun sent(r: HttpRequest): Map<String, String>? =
            r.authorization()?.let { challengesOf(Headers(listOf("WWW-Authenticate" to it))).single().params }

        private fun accepts(r: HttpRequest): Boolean {
            val p = sent(r) ?: return false
            if (p["nonce"] != nonce) return false
            val challenge = Challenge("digest", mapOf("realm" to "r", "nonce" to nonce, "qop" to "auth", "algorithm" to "SHA-256"))
            return digestAuthorization(challenge, valid, r.method, p.getValue("uri"), p.getValue("nc").toLong(16), p.getValue("cnonce")) ==
                r.authorization()
        }

        val respond: suspend (HttpRequest) -> HttpResponse = { r ->
            if (accepts(r)) {
                response(200, finalUrl = r.url)
            } else {
                val stale = sent(r)?.get("nonce")?.let { it != nonce } == true
                val header = "Digest realm=\"r\", nonce=\"$nonce\", qop=\"auth\", algorithm=SHA-256" + if (stale) ", stale=true" else ""
                if (rotate) {
                    rotate = false
                    nonce = "n2"
                }
                response(401, "WWW-Authenticate" to header, finalUrl = r.url, body = tracked())
            }
        }
    }

    @Test
    fun basicChallengeIsAnswered() =
        runTest {
            store.set("https://h", user)
            val transport = FakeTransport(basicServer())
            auth(transport).open(HttpRequest("https://h/f")).status shouldBe 200
            transport.requests.map { it.authorization() } shouldBe listOf(null, "Basic dTpw")
            bodies.single().closed shouldBe true
        }

    @Test
    fun digestChallengeIsAnswered() =
        runTest {
            store.set("https://h", user)
            val server = DigestServer()
            val transport = FakeTransport(server.respond)
            auth(transport).open(HttpRequest("https://h/f")).status shouldBe 200
            transport.requests.size shouldBe 2
            server.sent(transport.requests[1])!!["nc"] shouldBe "00000001"
        }

    @Test
    fun theNextRequestIsPreemptive() =
        runTest {
            store.set("https://h", user)
            val server = DigestServer()
            val transport = FakeTransport(server.respond)
            val auth = auth(transport)
            auth.open(HttpRequest("https://h/f"))
            auth.open(HttpRequest("https://h/g")).status shouldBe 200
            transport.requests.size shouldBe 3
            server.sent(transport.requests[2])!!["nc"] shouldBe "00000002"
        }

    @Test
    fun ownAuthorizationWins() =
        runTest {
            store.set("https://h", user)
            val transport = FakeTransport(basicServer())
            val sent = HttpRequest("https://h/f", headers = listOf("Authorization" to "Bearer t"))
            auth(transport).open(sent).status shouldBe 401
            transport.requests shouldBe listOf(sent)
        }

    @Test
    fun aStaleNonceIsRetriedOnce() =
        runTest {
            store.set("https://h", user)
            val server = DigestServer(rotate = true)
            val transport = FakeTransport(server.respond)
            auth(transport).open(HttpRequest("https://h/f")).status shouldBe 200
            transport.requests.map { server.sent(it)?.get("nonce") } shouldBe listOf(null, "n1", "n2")
        }

    @Test
    fun aWrongPasswordGivesThe401() =
        runTest {
            store.set("https://h", Credentials("u", "x"))
            val transport = FakeTransport(DigestServer().respond)
            val response = auth(transport).open(HttpRequest("https://h/f"))
            response.status shouldBe 401
            transport.requests.size shouldBe 2
            bodies.map { it.closed } shouldBe listOf(true, false)
        }

    @Test
    fun noCredentialsGivesThe401() =
        runTest {
            val transport = FakeTransport(basicServer())
            auth(transport).open(HttpRequest("https://h/f")).status shouldBe 401
            transport.requests.size shouldBe 1
        }

    @Test
    fun anUnsupportedSchemeGivesThe401() =
        runTest {
            store.set("https://h", user)
            val transport = FakeTransport { response(401, "WWW-Authenticate" to "NTLM") }
            auth(transport).open(HttpRequest("https://h/f")).status shouldBe 401
            transport.requests.size shouldBe 1
        }

    @Test
    fun urlUserInfoIsTheFallback() =
        runTest {
            val transport = FakeTransport(basicServer())
            auth(transport).open(HttpRequest("https://u:p@h/f")).status shouldBe 200
            transport.requests.last().authorization() shouldBe "Basic dTpw"
        }

    @Test
    fun theStoreWinsOverUserInfo() =
        runTest {
            store.set("https://h", user)
            val transport = FakeTransport(basicServer())
            auth(transport).open(HttpRequest("https://u:wrong@h/f")).status shouldBe 200
        }

    @Test
    fun cachedCredentialsServeTheOrigin() =
        runTest {
            val transport = FakeTransport(basicServer())
            val auth = auth(transport)
            auth.open(HttpRequest("https://u:p@h/f"))
            auth.open(HttpRequest("https://h/g")).status shouldBe 200
            transport.requests.size shouldBe 3
            transport.requests.last().authorization() shouldBe "Basic dTpw"
        }

    @Test
    fun storeCredentialsStayOnTheirOrigin() =
        runTest {
            store.set("https://h", user)
            val transport = FakeTransport(basicServer { "https://other/f" })
            auth(transport).open(HttpRequest("https://h/f")).status shouldBe 401
            transport.requests.size shouldBe 1
        }

    @Test
    fun userInfoStaysOnItsOrigin() =
        runTest {
            val transport = FakeTransport(basicServer { "https://other/f" })
            auth(transport).open(HttpRequest("https://u:p@h/f")).status shouldBe 401
            transport.requests.size shouldBe 1
        }

    @Test
    fun theRetryGoesToTheFinalUrl() =
        runTest {
            store.set("https://h", user)
            val transport = FakeTransport(basicServer { "https://h/g" })
            auth(transport).open(HttpRequest("https://h/f", headers = listOf("X-A" to "1")))
            transport.requests[1].url shouldBe "https://h/g"
            transport.requests[1].headers shouldBe listOf("X-A" to "1", "Authorization" to "Basic dTpw")
        }

    @Test
    fun parallelDigestNcIsUnique() =
        runTest {
            store.set("https://h", user)
            val server = DigestServer()
            val transport = FakeTransport(server.respond)
            val auth = auth(transport)
            auth.open(HttpRequest("https://h/f"))
            repeat(16) { launch { auth.open(HttpRequest("https://h/f")) } }
            testScheduler.advanceUntilIdle()
            val counts = transport.requests.drop(2).map { server.sent(it)!!.getValue("nc") }
            counts.size shouldBe 16
            counts.shouldBeUnique()
        }

    @Test
    fun digestUriIsTheRequestTarget() =
        runTest {
            store.set("https://h", user)
            val server = DigestServer()
            val transport = FakeTransport(server.respond)
            val auth = auth(transport)
            auth.open(HttpRequest("https://h/a%20b/f.zip?x=1&y=2#frag")).status shouldBe 200
            server.sent(transport.requests.last())!!["uri"] shouldBe "/a%20b/f.zip?x=1&y=2"
            auth.open(HttpRequest("https://h?q")).status shouldBe 200
            server.sent(transport.requests.last())!!["uri"] shouldBe "/?q"
        }

    @Test
    fun aFailedRetryClosesTheChallenge() =
        runTest {
            store.set("https://h", user)
            val serve = basicServer()
            val transport =
                FakeTransport { r -> if (r.authorization() == null) serve(r) else throw HttpIoException(HttpIoKind.RESET, "h") }
            shouldThrow<HttpIoException> { auth(transport).open(HttpRequest("https://h/f")) }.kind shouldBe HttpIoKind.RESET
            bodies.single().closed shouldBe true
        }

    @Test
    fun withoutAChallengeNothingIsSent() =
        runTest {
            store.set("https://h", user)
            val transport = FakeTransport { response(200) }
            auth(transport).open(HttpRequest("https://h/f"))
            transport.requests
                .single()
                .authorization()
                .shouldBeNull()
        }
}
