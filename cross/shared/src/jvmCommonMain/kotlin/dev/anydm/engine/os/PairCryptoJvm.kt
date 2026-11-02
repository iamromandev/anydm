package dev.anydm.engine.os

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.random.asKotlinRandom

internal actual fun hmacSha256(
    key: ByteArray,
    message: ByteArray,
): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(message)
}

internal actual fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

internal actual fun constantTimeEquals(
    a: ByteArray,
    b: ByteArray,
): Boolean = MessageDigest.isEqual(a, b)

internal actual fun secureRandom(): Random = SecureRandom().asKotlinRandom()
