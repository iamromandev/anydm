package dev.anydm.engine.os

/**
 * The messages and proofs of the browser pairing protocol (see `ui/apps/extension/contract/pairing-vectors.json`, which both
 * this app and the extension's tests read). A message is text made by joining its parts with `|`; nonces, ids and hashes are
 * written as lowercase hex, the counter and the port in decimal; every result is lowercase hex of an HMAC-SHA-256.
 *
 * Every proof except [secret] ends with the TCP port of the server the extension is talking to: the one the extension dialed,
 * and the one the app listens on. A relay on another port can pass a request on to the real app, but what the extension
 * proved was proved for the relay's port, so the real app refuses it, and what the real app proves is proved for its own
 * port, so the extension refuses that. The relay learns proofs it cannot use, and the code's 80 bits are too many to find
 * from them.
 */
internal object PairingProtocol {
    /** 16 characters of 5 bits each: 80 bits. */
    const val CODE_LENGTH = 16

    /** A code is shown in groups of this many characters. */
    const val GROUP_SIZE = 4

    /** Crockford's base32: no I, L, O or U. */
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    private val PATHS = setOf("/add", "/confirm")

    /**
     * The code as it is hashed: sixteen characters of [ALPHABET], upper case. What a person types is read the way Crockford
     * says: spaces, tabs, line ends and hyphens are dropped, case is ignored, and O, I and L stand for 0, 1 and 1. Only ASCII
     * is read: a letter or a space of another script that Unicode would fold into one of these (a dotless i, a long s, a
     * no-break space) makes the whole input not a code. Null if [input] cannot be a code.
     */
    fun normaliseCode(input: String): String? {
        val canonical = StringBuilder()
        for (c in input) {
            if (c == '-' || c == ' ' || c == '\t' || c == '\n' || c == '\r') continue
            canonical.append(crockford(c) ?: return null)
            if (canonical.length > CODE_LENGTH) return null
        }
        return canonical.toString().takeIf { it.length == CODE_LENGTH }
    }

    // One ASCII character as the alphabet has it; null for anything else.
    private fun crockford(c: Char): Char? {
        val upper = if (c in 'a'..'z') (c.code - 'a'.code + 'A'.code).toChar() else c
        val read =
            when (upper) {
                'O' -> '0'
                'I', 'L' -> '1'
                else -> upper
            }
        return read.takeIf { it in ALPHABET }
    }

    /** A canonical [code] for people to read: groups of [GROUP_SIZE] joined with hyphens, which [normaliseCode] takes back. */
    fun grouped(code: String): String = code.chunked(GROUP_SIZE).joinToString("-")

    /** What the extension proves it knows the code with: `anydm-pair-1|nonceE|port`. */
    fun pairProof(
        code: String,
        nonceE: String,
        port: Int,
    ): String = hex(code.encodeToByteArray(), "anydm-pair-1", nonceE, portText(port))

    /** What the app proves it knows the code with: `anydm-app-1|nonceE|nonceA|port`. */
    fun appProof(
        code: String,
        nonceE: String,
        nonceA: String,
        port: Int,
    ): String = hex(code.encodeToByteArray(), "anydm-app-1", nonceE, nonceA, portText(port))

    /** The 32 bytes both sides keep, as hex: `anydm-secret-1|nonceE|nonceA`. It has no port: it is not a proof to anyone. */
    fun secret(
        code: String,
        nonceE: String,
        nonceA: String,
    ): String = hex(code.encodeToByteArray(), "anydm-secret-1", nonceE, nonceA)

    /** What the extension proves it holds the secret with, when it starts a session: `anydm-hello-req-1|id|nonceE|port`. */
    fun helloRequestProof(
        secret: String,
        id: String,
        nonceE: String,
        port: Int,
    ): String = hex(keyOf(secret), "anydm-hello-req-1", id, nonceE, portText(port))

    /** What the app proves it holds the secret with, when a session starts: `anydm-hello-1|nonceE|nonceA|port`. */
    fun helloProof(
        secret: String,
        nonceE: String,
        nonceA: String,
        port: Int,
    ): String = hex(keyOf(secret), "anydm-hello-1", nonceE, nonceA, portText(port))

    /** The hash of a request body, as hex. */
    fun bodyHash(body: ByteArray): String = sha256(body).toHex()

    /**
     * What a request is signed with: `anydm-req-1|nonceA|counter|path|sha256(body)|port`. The [counter] strictly increases in a
     * session. Only `/add` and `/confirm` are signed.
     */
    fun requestMac(
        secret: String,
        nonceA: String,
        counter: Long,
        path: String,
        body: ByteArray,
        port: Int,
    ): String {
        require(path in PATHS) { "only /add and /confirm are signed" }
        return hex(keyOf(secret), "anydm-req-1", nonceA, counter.toString(), path, bodyHash(body), portText(port))
    }

    private fun portText(port: Int): String {
        require(port in 1..MAX_PORT) { "not a port: $port" }
        return port.toString()
    }

    private fun keyOf(secret: String): ByteArray = requireNotNull(secret.hexToBytes()) { "the secret is hex" }

    private fun hex(
        key: ByteArray,
        vararg parts: String,
    ): String = hmacSha256(key, parts.joinToString("|").encodeToByteArray()).toHex()

    private const val MAX_PORT = 65535
}
