package dev.anydm.engine.net

/** A [Bond] the test steers: the networks in use now, their speeds, and the faults a download reported. */
internal class FakeBond(
    var networks: List<NetworkInfo>,
    var speeds: Map<String, Double> = emptyMap(),
) : Bond {
    val faults = mutableListOf<String>()

    override fun active(): List<NetworkInfo> = networks

    override fun speeds(): Map<String, Double> = speeds

    override fun isActive(key: String): Boolean = networks.any { it.key == key }

    override suspend fun fault(key: String) {
        faults += key
        networks = networks.filterNot { it.key == key }
    }
}
