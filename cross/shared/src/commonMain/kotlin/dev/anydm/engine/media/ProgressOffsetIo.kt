package dev.anydm.engine.media

import dev.anydm.engine.protocol.TransferIo

/**
 * One stream's transfer sees [io] as its own, but progress is the whole job's: the bytes of the job's other files,
 * [others], are added to what this stream has.
 */
internal class ProgressOffsetIo(
    private val io: TransferIo,
    private val others: Long,
) : TransferIo by io {
    /** The most this stream has reported, without the offset: the bytes it holds when it is done. */
    var last: Long = 0L
        private set

    override fun progress(downloaded: Long) {
        last = maxOf(last, downloaded)
        io.progress(downloaded + others)
    }
}
