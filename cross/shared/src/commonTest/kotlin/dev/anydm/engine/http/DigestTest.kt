package dev.anydm.engine.http

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import kotlin.io.encoding.Base64
import kotlin.test.Test

class DigestTest {
    private val mufasa = Credentials("Mufasa", "Circle of Life")
    private val realm = "http-auth@example.org"
    private val nonce = "7ypf/xlj9XXwfDPEoM4URrv/xwf94BcCAzFZH4GiTo0v"
    private val opaque = "FQhe/qaU925kfnzjCev0ciny7QMkPqMAFRtzCUYo5tdS"
    private val cnonce = "f2/wE4q74E6zIJEtWaHKaf5wv/H5QzzpXusqGemxURZJ"

    private fun challenge(
        algorithm: String?,
        qop: String? = "auth, auth-int",
    ) = Challenge(
        "digest",
        listOfNotNull(
            "realm" to realm,
            qop?.let { "qop" to it },
            algorithm?.let { "algorithm" to it },
            "nonce" to nonce,
            "opaque" to opaque,
        ).toMap(),
    )

    private fun md5(text: String) = hashHex(HashAlgorithm.MD5, text.encodeToByteArray())

    private fun sha256(text: String) = hashHex(HashAlgorithm.SHA_256, text.encodeToByteArray())

    @Test
    fun rfc7616Md5() {
        digestAuthorization(challenge("MD5"), mufasa, "GET", "/dir/index.html", 1, cnonce) shouldContain
            "response=\"8ca523f5e9506fed4657c9700eebdbec\""
    }

    @Test
    fun rfc7616Sha256() {
        val header = digestAuthorization(challenge("SHA-256"), mufasa, "GET", "/dir/index.html", 1, cnonce)
        header shouldContain "response=\"753927fa0e85d155564e2e272a28d1802ca10daf4496794697cf8db5856cb6c1\""
        header shouldStartWith "Digest "
        listOf(
            "username=\"Mufasa\"",
            "realm=\"$realm\"",
            "nonce=\"$nonce\"",
            "uri=\"/dir/index.html\"",
            "algorithm=SHA-256",
            "qop=auth",
            "nc=00000001",
            "cnonce=\"$cnonce\"",
            "opaque=\"$opaque\"",
        ).forEach { header shouldContain it }
    }

    @Test
    fun sessVariant() {
        val ha1 = sha256("${sha256("Mufasa:$realm:Circle of Life")}:$nonce:$cnonce")
        val ha2 = sha256("GET:/f")
        val header = digestAuthorization(challenge("SHA-256-sess"), mufasa, "GET", "/f", 3, cnonce)
        header shouldContain "response=\"${sha256("$ha1:$nonce:00000003:$cnonce:auth:$ha2")}\""
        header shouldContain "algorithm=SHA-256-sess"
    }

    @Test
    fun noQop() {
        val ha1 = md5("Mufasa:$realm:Circle of Life")
        val header = digestAuthorization(challenge(null, qop = null), mufasa, "GET", "/f", 1, cnonce)
        header shouldContain "response=\"${md5("$ha1:$nonce:${md5("GET:/f")}")}\""
        header shouldNotContain "qop="
        header shouldNotContain "nc="
        header shouldNotContain "cnonce="
        header shouldNotContain "algorithm="
    }

    @Test
    fun ncIsEightHexDigits() {
        digestAuthorization(challenge("MD5"), mufasa, "GET", "/", 255, "c") shouldContain "nc=000000ff"
    }

    @Test
    fun quotedValuesAreEscaped() {
        digestAuthorization(challenge("MD5"), Credentials("a\"b\\c", "p"), "GET", "/", 1, "c") shouldContain
            "username=\"a\\\"b\\\\c\""
    }

    @Test
    fun basic() {
        basicAuthorization(Credentials("Aladdin", "open sesame")) shouldBe "Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ=="
    }

    @Test
    fun nonAsciiCredentialsUseUtf8() {
        val jurgen = Credentials("jürgen", "pässword")
        basicAuthorization(jurgen) shouldBe "Basic " + Base64.encode("jürgen:pässword".encodeToByteArray())
        val ha1 = md5("jürgen:$realm:pässword")
        val ha2 = md5("GET:/")
        digestAuthorization(challenge("MD5"), jurgen, "GET", "/", 1, "c") shouldContain
            "response=\"${md5("$ha1:$nonce:00000001:c:auth:$ha2")}\""
    }

    @Test
    fun parsesSeveralChallenges() {
        val headers =
            Headers(
                listOf(
                    "WWW-Authenticate" to "Basic realm=\"a, b\", Digest realm=\"r\", nonce=\"n\\\"x\", qop=\"auth\"",
                    "WWW-Authenticate" to "Bearer abc123==",
                    "WWW-Authenticate" to "Digest realm=\"r\", nonce=\"n2\", algorithm=SHA-256",
                ),
            )
        val challenges = challengesOf(headers)
        challenges.map { it.scheme } shouldBe listOf("basic", "digest", "bearer", "digest")
        challenges[0].params["realm"] shouldBe "a, b"
        challenges[1].params["nonce"] shouldBe "n\"x"
        challenges[2].params shouldBe emptyMap()
        chooseChallenge(challenges)!!.params["nonce"] shouldBe "n2"
    }

    @Test
    fun basicIsTheLastChoice() {
        val headers = Headers(listOf("WWW-Authenticate" to "Basic realm=\"r\", Digest realm=\"r\", nonce=\"n\""))
        chooseChallenge(challengesOf(headers))!!.scheme shouldBe "digest"
        chooseChallenge(challengesOf(Headers(listOf("WWW-Authenticate" to "Basic realm=\"r\""))))!!.scheme shouldBe "basic"
    }

    @Test
    fun challengeNamesIgnoreCase() {
        val headers = Headers(listOf("www-authenticate" to "digest Realm=\"r\", NONCE=\"n\", ALGORITHM=sha-256, QOP=\"auth\""))
        val chosen = chooseChallenge(challengesOf(headers))!!
        chosen.params["algorithm"] shouldBe "sha-256"
        val header = digestAuthorization(chosen, mufasa, "GET", "/", 1, "c")
        header shouldContain "algorithm=SHA-256"
        header shouldContain "response=\"${sha256("${sha256("Mufasa:r:Circle of Life")}:n:00000001:c:auth:${sha256("GET:/")}")}\""
    }

    @Test
    fun unsupportedChallenges() {
        val values =
            listOf(
                "Digest realm=\"r\", nonce=\"n\", qop=\"auth-int\"",
                "Digest realm=\"r\", nonce=\"n\", userhash=true",
                "Digest realm=\"r\", nonce=\"n\", algorithm=SHA-512-256",
                "Digest realm=\"r\"",
                "NTLM",
                "Negotiate abc",
            )
        for (value in values) {
            withClue(value) { chooseChallenge(challengesOf(Headers(listOf("WWW-Authenticate" to value)))).shouldBeNull() }
        }
    }

    @Test
    fun credentialsRules() {
        shouldThrow<IllegalArgumentException> { Credentials("a:b", "p") }
        shouldThrow<IllegalArgumentException> { Credentials("a", "p\r\n") }
        shouldThrow<IllegalArgumentException> { Credentials("a\u0000", "p") }
        Credentials("a", "secret").toString() shouldNotContain "secret"
    }
}
