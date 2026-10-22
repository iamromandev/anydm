package dev.anydm.engine.protocol

/** Which way counted bytes went: [DOWN] arrived, [UP] was sent (a torrent seeds, TOR-09). */
enum class Direction { DOWN, UP }

/** Counts bytes received and sent for speed and totals (NET-04); [None] counts nothing. */
interface Meter {
    /**
     * Counts [bytes]; a protocol that only downloads leaves [direction] at [Direction.DOWN]. A connection bound to a network
     * (#544) passes that network's key as [network], so the bytes are counted under it; null is the default route.
     */
    fun record(
        bytes: Long,
        direction: Direction = Direction.DOWN,
        network: String? = null,
    )

    data object None : Meter {
        override fun record(
            bytes: Long,
            direction: Direction,
            network: String?,
        ) = Unit
    }
}
