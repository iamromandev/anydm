package dev.anydm.engine.torrent

import dev.anydm.engine.http.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/** The numbers a re-check runs by. */
internal class RecheckEnv(
    val clock: Clock,
    /** Where pieces are read: blocking disk reads. */
    val io: CoroutineDispatcher,
    /** The most piece data the check holds at once: the pieces being read or hashed together are never more than this. */
    val cap: Long,
    /** How often a [Recheck] is told how far it is. */
    val reportMs: Long,
    /** How often the clock is looked at. */
    val tickMs: Long,
    val cpus: Int = Runtime.getRuntime().availableProcessors(),
)

/**
 * How many pieces of [pieceLength] bytes may be read and hashed at once without holding more than [cap] bytes: one per CPU at most,
 * and one at least, so a piece bigger than the cap is still checked.
 */
internal fun recheckWorkers(
    pieceLength: Long,
    cap: Long,
    cpus: Int,
): Int = (cap / pieceLength.coerceAtLeast(1)).coerceIn(1, cpus.coerceAtLeast(1).toLong()).toInt()

/**
 * The pieces of [claimed] whose bytes on disk still hash to what the metainfo says: what a resume may trust. A piece whose files are
 * missing, too short or changed is left out (a missing file is made again, zero-filled, when the store opens, which fails the
 * hash of everything in it). Pieces are read in order, at most [recheckWorkers] at a time and hashed away from the reading
 * threads, so the memory held and the cores used are bounded; [progress] is told the bytes verified so far every [RecheckEnv.reportMs].
 *
 * A cancel ends it at the next piece. Nothing is changed on disk.
 */
internal suspend fun recheck(
    env: RecheckEnv,
    store: TorrentStore,
    meta: TorrentMeta,
    claimed: PieceSet,
    progress: suspend (doneBytes: Long) -> Unit,
): PieceSet {
    val todo = (0 until meta.pieceCount).filter { claimed.has(it) }
    if (todo.isEmpty()) return claimed
    val good = BooleanArray(meta.pieceCount)
    val doneBytes = AtomicLong()
    val gate = Semaphore(recheckWorkers(meta.pieceLength, env.cap, env.cpus))
    coroutineScope {
        val ticker =
            launch {
                var last = env.clock.nowMs()
                while (true) {
                    delay(env.tickMs)
                    val now = env.clock.nowMs()
                    // due a tick early, as the run's reports are: a late tick never pushes a report far past its time
                    if (now - last >= env.reportMs - env.tickMs) {
                        last = now
                        progress(doneBytes.get())
                    }
                }
            }
        coroutineScope {
            for (piece in todo) {
                gate.acquire()
                launch {
                    try {
                        val bytes = withContext(env.io) { store.readPiece(piece) }
                        if (bytes != null && withContext(Dispatchers.Default) { sha1(bytes).contentEquals(meta.pieceHashes[piece]) }) {
                            good[piece] = true
                            doneBytes.addAndGet(bytes.size.toLong())
                        }
                    } finally {
                        gate.release()
                    }
                }
            }
        }
        ticker.cancel()
    }
    return PieceSet.of(meta.pieceCount, todo.filter { good[it] })
}
