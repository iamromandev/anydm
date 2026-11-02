package dev.anydm.desktop

import dev.anydm.engine.net.NetworkInfo
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * [address] (an IPv4 address) with the host bits of its [prefix] cleared, as `192.168.1.0/24`; null for an IPv6 address or
 * text that is no address.
 */
internal fun subnetText(
    address: String,
    prefix: Int,
): String? {
    val parts = address.split('.')
    if (parts.size != 4 || prefix !in 0..32) return null
    val octets = parts.map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return null }
    val value = octets.fold(0L) { acc, octet -> (acc shl 8) or octet.toLong() }
    val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
    val network = value and mask
    return "${(network shr 24) and 255}.${(network shr 16) and 255}.${(network shr 8) and 255}.${network and 255}/$prefix"
}

/** The prefix length the OS has for [address] on [info]'s interface, null when it can't say. */
internal fun osPrefix(
    info: NetworkInfo,
    address: String,
): Int? =
    try {
        NetworkInterface
            .getByName(info.key)
            ?.interfaceAddresses
            ?.firstOrNull { it.address == InetAddress.getByName(address) }
            ?.networkPrefixLength
            ?.toInt()
    } catch (_: Exception) {
        null
    }

/**
 * The IPv4 subnet [info] is on, from its first IPv4 address and the prefix [prefixOf] knows for it; the engine gives addresses
 * only, so with no prefix a /24 is assumed (a home router's usual). Null when it has no IPv4 address.
 */
internal fun subnetOf(
    info: NetworkInfo,
    prefixOf: (NetworkInfo, String) -> Int? = ::osPrefix,
): String? {
    val address = info.addresses.firstOrNull { ':' !in it && it.count { c -> c == '.' } == 3 } ?: return null
    return subnetText(address, prefixOf(info, address) ?: 24)
}
