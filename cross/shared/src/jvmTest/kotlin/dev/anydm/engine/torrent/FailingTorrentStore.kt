package dev.anydm.engine.torrent

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.store.TorrentStore

/** A torrent store that can be told to fail the saves of resume data and of file progress (a full disk, a busy database). */
internal class FailingTorrentStore(
    private val delegate: TorrentStore,
) : TorrentStore by delegate {
    var failingResume = false
    var failingFileDone = false

    override suspend fun setResume(
        id: DownloadId,
        bytes: ByteArray?,
    ): Boolean {
        if (failingResume) throw IllegalStateException("disk full")
        return delegate.setResume(id, bytes)
    }

    override suspend fun setFileDone(
        id: DownloadId,
        done: List<Long>?,
    ): Boolean {
        if (failingFileDone) throw IllegalStateException("disk full")
        return delegate.setFileDone(id, done)
    }
}
