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
)

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
