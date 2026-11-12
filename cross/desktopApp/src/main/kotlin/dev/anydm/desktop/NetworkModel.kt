package dev.anydm.desktop

const val MAX_NETWORK_NAME_LENGTH = 24

enum class NetworkKind(
    val label: String,
) {
    WiFi("Wi-Fi"),
    UsbTethering("USB Tethering"),
}

data class Network(
    val id: String,
    val name: String,
    val kind: NetworkKind,
    val enabled: Boolean,
    val bytesPerSec: Long,
    val color: NetworkColor? = null,
    val proxy: Proxy = Proxy.NONE,
)

enum class ProxyType(
    val label: String,
) {
    None("None"),
    Http("HTTP"),
    Socks5("SOCKS5"),
}

data class Proxy(
    val type: ProxyType,
    val host: String = "",
    val port: Int = 0,
    val user: String = "",
    val password: String = "",
) {
    /** The password is masked so it never lands in logs or test failure messages. */
    override fun toString() = "Proxy(type=$type, host=$host, port=$port, user=$user, password=${mask(password)})"

    companion object {
        val NONE = Proxy(ProxyType.None)
    }
}

/** Raw editor text, validated by [proxyErrors] and turned into a [Proxy] by [proxyOf]. */
data class ProxyDraft(
    val type: ProxyType,
    val host: String,
    val port: String,
    val user: String,
    val password: String,
) {
    override fun toString() = "ProxyDraft(type=$type, host=$host, port=$port, user=$user, password=${mask(password)})"
}

private fun mask(password: String) = if (password.isEmpty()) "" else "***"

data class ProxyErrors(
    val host: String?,
    val port: String?,
    val auth: String?,
) {
    val any get() = host != null || port != null || auth != null
}

/**
 * A host name (letters, digits, hyphens, dots; optional trailing dot; labels up to 63 characters) or an IPv4 address.
 * When every label is numeric it must be a dotted quad of 0-255. No scheme, path, port, space or IPv6.
 */
fun isHost(s: String): Boolean {
    if (s.isEmpty()) return false
    val labels = s.removeSuffix(".").split('.')
    val valid =
        labels.all { l ->
            l.isNotEmpty() &&
                l.length <= 63 &&
                !l.startsWith('-') &&
                l.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
        }
    if (!valid) return false
    val numeric = labels.all { l -> l.all { it in '0'..'9' } }
    return !numeric || (labels.size == 4 && labels.all { l -> l.length <= 3 && l.toInt() in 0..255 })
}

private fun parsePort(s: String): Int? =
    if (s.isNotEmpty() &&
        s.all { it in '0'..'9' }
    ) {
        s.toIntOrNull()?.takeIf { it in 1..65535 }
    } else {
        null
    }

fun proxyErrors(d: ProxyDraft): ProxyErrors {
    if (d.type == ProxyType.None) return ProxyErrors(null, null, null)
    return ProxyErrors(
        host = if (isHost(d.host)) null else "Enter a host name or IPv4 address",
        port = if (parsePort(d.port) != null) null else "Port must be 1\u201365535",
        auth = if (d.password.isNotEmpty() && d.user.isEmpty()) "Add a username for this password" else null,
    )
}

fun proxyOf(d: ProxyDraft): Proxy? {
    if (d.type == ProxyType.None) return Proxy.NONE
    if (proxyErrors(d).any) return null
    return Proxy(d.type, d.host, parsePort(d.port) ?: return null, d.user, d.password)
}

fun draftOf(p: Proxy): ProxyDraft =
    if (p.type == ProxyType.None) {
        ProxyDraft(ProxyType.None, "", "", "", "")
    } else {
        ProxyDraft(p.type, p.host, p.port.toString(), p.user, p.password)
    }

/** The password is never part of the summary. */
fun proxySummary(p: Proxy): String =
    if (p.type == ProxyType.None) "None" else "${p.type.label} ${if (p.user.isEmpty()) "" else "${p.user}@"}${p.host}:${p.port}"

fun setProxy(
    networks: List<Network>,
    id: String,
    proxy: Proxy,
): List<Network> = networks.map { if (it.id == id) it.copy(proxy = proxy) else it }

sealed interface RenameResult {
    data class Ok(
        val networks: List<Network>,
    ) : RenameResult

    data class Rejected(
        val reason: String,
    ) : RenameResult
}

fun enabledCount(networks: List<Network>): Int = networks.count { it.enabled }

fun totalSpeed(networks: List<Network>): Long = networks.filter { it.enabled }.sumOf { it.bytesPerSec }

fun networkLabel(count: Int): String = if (count == 1) "1 network" else "$count networks"

/** Flips [id]; the last enabled network is never turned off, so the same list comes back. */
fun toggle(
    networks: List<Network>,
    id: String,
): List<Network> {
    val target = networks.firstOrNull { it.id == id } ?: return networks
    if (target.enabled && enabledCount(networks) <= 1) return networks
    return networks.map { if (it.id == id) it.copy(enabled = !it.enabled) else it }
}

fun setColor(
    networks: List<Network>,
    id: String,
    color: NetworkColor,
): List<Network> = networks.map { if (it.id == id) it.copy(color = color) else it }

fun rename(
    networks: List<Network>,
    id: String,
    raw: String,
): RenameResult {
    val name = raw.trim()
    return when {
        name.isEmpty() -> RenameResult.Rejected("Name can't be empty")
        name.length > MAX_NETWORK_NAME_LENGTH -> RenameResult.Rejected("Max $MAX_NETWORK_NAME_LENGTH characters")
        networks.any { it.id != id && it.name.equals(name, ignoreCase = true) } -> RenameResult.Rejected("Name already used")
        else -> RenameResult.Ok(networks.map { if (it.id == id) it.copy(name = name) else it })
    }
}
