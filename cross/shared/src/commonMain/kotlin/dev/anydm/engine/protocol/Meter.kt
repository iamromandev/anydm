package dev.anydm.engine.protocol

/** Which way counted bytes went: [DOWN] arrived, [UP] was sent (a torrent seeds, TOR-09). */
enum class Direction { DOWN, UP }

/** Counts bytes received and sent for speed and totals (NET-04); [None] counts nothing. */
interface Meter {
    /** Counts [bytes]; a protocol that only downloads leaves [direction] at [Direction.DOWN]. */
    fun record(
        bytes: Long,
        direction: Direction = Direction.DOWN,
    )

    data object None : Meter {
        override fun record(
            bytes: Long,
            direction: Direction,
        ) = Unit
    }
}
