package dev.anydm.engine.net

/**
 * What each network counts for when work is shared out (BND-03): its measured speed, and for one that has none yet
 * (new, or idle until now) the average of the ones that have, so it is measured on a real share instead of starved.
 * With no speed at all everyone counts the same.
 */
internal fun weightsOf(
    speeds: Map<String, Double>,
    keys: Collection<String>,
): Map<String, Double> {
    val known = keys.mapNotNull { key -> speeds[key]?.takeIf { it > 0 && it.isFinite() } }
    val fallback = if (known.isEmpty()) 1.0 else known.average()
    return keys.associateWith { key -> speeds[key]?.takeIf { it > 0 && it.isFinite() } ?: fallback }
}

/**
 * [total] connections shared among the networks in [speeds] in proportion to their speed, as whole numbers that add up
 * (largest remainder, ties to the faster network and then the smaller key, so the same input always gives the same
 * answer). Every network gets at least one, so its speed can be measured: with fewer than one connection per network the
 * sum is the number of networks.
 */
internal fun apportion(
    total: Int,
    speeds: Map<String, Double>,
): Map<String, Int> {
    val keys = speeds.keys.sorted()
    if (keys.isEmpty()) return emptyMap()
    val target = maxOf(total, keys.size)
    val weights = weightsOf(speeds, keys)
    val sum = weights.values.sum()
    val ideal = keys.associateWith { target * weights.getValue(it) / sum }
    val shares = keys.associateWithTo(mutableMapOf()) { ideal.getValue(it).toInt() }
    val byRemainder =
        keys.sortedWith(
            compareByDescending<String> { ideal.getValue(it) - shares.getValue(it) }
                .thenByDescending { weights.getValue(it) }
                .thenBy { it },
        )
    repeat(target - shares.values.sum()) { shares[byRemainder[it % keys.size]] = shares.getValue(byRemainder[it % keys.size]) + 1 }
    // A network that rounded to nothing takes one from the one with the most (the slowest of the biggest, so order holds).
    for (key in keys.filter { shares.getValue(it) == 0 }) {
        val donor =
            keys
                .filter { shares.getValue(it) > 1 }
                .sortedWith(compareByDescending<String> { shares.getValue(it) }.thenBy { weights.getValue(it) }.thenBy { it })
                .first()
        shares[donor] = shares.getValue(donor) - 1
        shares[key] = 1
    }
    return keys.associateWith { shares.getValue(it) }
}

/**
 * The network that should get the next connection: the one furthest below its [targets] share, the faster first on a tie;
 * null when every network has its share. [current] has the connections now, none for a network it doesn't name.
 */
internal fun deficitPick(
    current: Map<String, Int>,
    targets: Map<String, Int>,
    speeds: Map<String, Double>,
): String? =
    targets.keys
        .filter { (targets.getValue(it) - (current[it] ?: 0)) > 0 }
        .sortedWith(
            compareByDescending<String> { targets.getValue(it) - (current[it] ?: 0) }
                .thenByDescending { speeds[it] ?: 0.0 }
                .thenBy { it },
        ).firstOrNull()

/**
 * The network a whole download (FTP, SFTP) should use: the one with the fewest downloads running per unit of speed, the
 * faster first on a tie. [running] has the downloads now, none for a network it doesn't name.
 */
internal fun pickForDownload(
    active: List<String>,
    running: Map<String, Int>,
    speeds: Map<String, Double>,
): String {
    require(active.isNotEmpty()) { "no network to pick from" }
    val weights = weightsOf(speeds, active)
    return active
        .sortedWith(
            compareBy<String> { (running[it] ?: 0) / weights.getValue(it) }
                .thenByDescending { weights.getValue(it) }
                .thenBy { it },
        ).first()
}
