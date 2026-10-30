package dev.anydm.engine.http

import kotlin.io.encoding.Base64

/** One `WWW-Authenticate` challenge: [scheme] and parameter names lowercased, values as sent (unquoted). */
internal class Challenge(
    val scheme: String,
    val params: Map<String, String>,
) {
    override fun toString(): String = "Challenge($scheme, ${params.keys})"
}

/** Every challenge in [headers]' `WWW-Authenticate` fields, several per field allowed; a token68 is skipped. */
internal fun challengesOf(headers: Headers): List<Challenge> {
    val challenges = mutableListOf<Pair<String, MutableMap<String, String>>>()
    for (value in headers.all("WWW-Authenticate")) {
        for (item in itemsOf(value)) {
            val param = paramOf(item)
            if (param != null) {
                challenges.lastOrNull()?.second?.put(param.first, param.second)
                continue
            }
            val scheme = token.matchAt(item, 0)?.value ?: continue
            val params = mutableMapOf<String, String>()
            val rest = item.substring(scheme.length).trim()
            paramOf(rest)?.let { params[it.first] = it.second }
            challenges += scheme.lowercase() to params
        }
    }
    return challenges.map { (scheme, params) -> Challenge(scheme, params) }
}

/**
 * The challenge to answer: Digest with SHA-256, then Digest with MD5 (or no algorithm), then Basic. A Digest without a
 * realm or nonce, asking for `userhash`, offering a qop other than `auth`, or with another algorithm, is unsupported.
 */
internal fun chooseChallenge(challenges: List<Challenge>): Challenge? =
    challenges
        .mapNotNull { challenge -> rankOf(challenge)?.let { challenge to it } }
        .maxByOrNull { it.second }
        ?.first

internal fun basicAuthorization(credentials: Credentials): String =
    "Basic " + Base64.encode("${credentials.user}:${credentials.password}".encodeToByteArray())

/** The `Authorization` answering a Digest [challenge] (RFC 7616) for [method] on [uri], as request [nc] of its nonce. */
internal fun digestAuthorization(
    challenge: Challenge,
    credentials: Credentials,
    method: String,
    uri: String,
    nc: Long,
    cnonce: String,
): String {
    val algorithm = challenge.params["algorithm"]?.lowercase()
    val hash = if (algorithm?.startsWith("sha-256") == true) HashAlgorithm.SHA_256 else HashAlgorithm.MD5

    fun h(text: String) = hashHex(hash, text.encodeToByteArray())
    val realm = challenge.params["realm"].orEmpty()
    val nonce = challenge.params["nonce"].orEmpty()
    val qop = challenge.params["qop"] != null
    val count = nc.toString(16).padStart(8, '0')
    val secret = h("${credentials.user}:$realm:${credentials.password}")
    val ha1 = if (algorithm?.endsWith("-sess") == true) h("$secret:$nonce:$cnonce") else secret
    val ha2 = h("$method:$uri")
    val response = if (qop) h("$ha1:$nonce:$count:$cnonce:auth:$ha2") else h("$ha1:$nonce:$ha2")
    return buildList {
        add("username=${quoted(credentials.user)}")
        add("realm=${quoted(realm)}")
        add("nonce=${quoted(nonce)}")
        add("uri=${quoted(uri)}")
        algorithm?.let { add("algorithm=${canonical.getValue(it)}") }
        add("response=\"$response\"")
        if (qop) {
            add("qop=auth")
            add("nc=$count")
            add("cnonce=${quoted(cnonce)}")
        }
        challenge.params["opaque"]?.let { add("opaque=${quoted(it)}") }
    }.joinToString(", ", prefix = "Digest ")
}

private val token = Regex("""[!#$%&'*+.^_`|~0-9A-Za-z-]+""")
private val param = Regex("""([!#$%&'*+.^_`|~0-9A-Za-z-]+)\s*=\s*(?:([!#$%&'*+.^_`|~0-9A-Za-z-]+)|"((?:[^"\\]|\\.)*)")""")
private val canonical = mapOf("md5" to "MD5", "md5-sess" to "MD5-sess", "sha-256" to "SHA-256", "sha-256-sess" to "SHA-256-sess")

/** [value] split at the commas outside quoted strings, each item trimmed, empty ones dropped. */
private fun itemsOf(value: String): List<String> {
    val items = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    var escaped = false
    for (c in value) {
        when {
            escaped -> {
                escaped = false
            }

            quoted && c == '\\' -> {
                escaped = true
            }

            c == '"' -> {
                quoted = !quoted
            }

            c == ',' && !quoted -> {
                items += current.toString().trim()
                current.clear()
                continue
            }
        }
        current.append(c)
    }
    items += current.toString().trim()
    return items.filter { it.isNotEmpty() }
}

/** `name=value` with a lowercased name and an unquoted value; null for anything else. */
private fun paramOf(item: String): Pair<String, String>? {
    val match = param.matchEntire(item) ?: return null
    val (name, plain, quotedValue) = match.destructured
    val value = if (plain.isNotEmpty()) plain else quotedValue.replace(Regex("""\\(.)"""), "$1")
    return name.lowercase() to value
}

private fun rankOf(challenge: Challenge): Int? =
    when (challenge.scheme) {
        "basic" -> 1
        "digest" -> digestRankOf(challenge.params)
        else -> null
    }

private fun digestRankOf(params: Map<String, String>): Int? {
    if (params["realm"] == null || params["nonce"] == null) return null
    if (params["userhash"].equals("true", ignoreCase = true)) return null
    val qop = params["qop"]
    if (qop != null && qop.split(',').none { it.trim().equals("auth", ignoreCase = true) }) return null
    return when (params["algorithm"]?.lowercase()) {
        "sha-256", "sha-256-sess" -> 3
        null, "md5", "md5-sess" -> 2
        else -> null
    }
}

private fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
