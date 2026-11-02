package dev.anydm.desktop

import dev.anydm.engine.SpeedWindow

/**
 * Turns a download's running per-network byte totals ([dev.anydm.engine.DownloadSnapshot.networkShare]) into one bytes
 * per second sample per network for each second, the last [size] kept: the engine only knows the speed of the whole.
 * Not thread safe; the caller ticks it from one place, about once a second.
 */
class NetworkSampler(
    private val size: Int = SpeedWindow.SIZE,
) {
    private var previous: Map<String, Long>? = null
    private val samples = LinkedHashMap<String, ArrayDeque<Long>>()

    /** How many seconds are held; the same for every network. */
    var length: Int = 0
        private set

    /** Each network's samples, oldest first, all [length] long. A network whose window is all zeros is not listed. */
    val series: Map<String, List<Long>>
        get() = samples.mapValues { it.value.toList() }

    /**
     * Takes the totals as they are now, [elapsedMillis] after the last call. The first call only sets what later ones are
     * measured against. A total below the one before is a restart of the count: what it holds now is what it gained.
     */
    fun tick(
        share: Map<String, Long>,
        elapsedMillis: Long = 1000,
    ) {
        val before = previous
        previous = share
        if (before == null) return
        val seconds = elapsedMillis.coerceAtLeast(1) / 1000.0
        for (key in share.keys) {
            if (key !in samples) samples[key] = ArrayDeque(List(length) { 0L })
        }
        for ((key, window) in samples) {
            val now = share[key]
            val last = before[key] ?: 0L
            val gained =
                if (now == null) {
                    0L
                } else if (now < last) {
                    now
                } else {
                    now - last
                }
            window.addLast((gained / seconds).toLong())
            if (window.size > size) window.removeFirst()
        }
        length = minOf(length + 1, size)
        samples.entries.removeAll { (_, window) -> window.all { it == 0L } && window.size >= size }
    }

    /** The latest sample of [key]; 0 for a network not listed. */
    fun latest(key: String): Long = samples[key]?.lastOrNull() ?: 0L

    fun reset() {
        previous = null
        samples.clear()
        length = 0
    }
}
