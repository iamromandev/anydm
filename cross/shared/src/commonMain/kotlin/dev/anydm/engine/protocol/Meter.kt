package dev.anydm.engine.protocol

/** Counts bytes received for speed and totals (NET-04); [None] counts nothing until #538 brings the real one. */
interface Meter {
    fun record(bytes: Long)

    data object None : Meter {
        override fun record(bytes: Long) = Unit
    }
}
