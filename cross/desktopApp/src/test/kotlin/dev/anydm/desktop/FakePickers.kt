package dev.anydm.desktop

import java.nio.file.Path

/** [Pickers] that answer what the test set, and count the times they were asked. */
internal class FakePickers(
    var torrent: Path? = null,
    var folder: Path? = null,
) : Pickers {
    var torrentAsked = 0
    var folderAsked = 0

    override fun torrentFile(): Path? {
        torrentAsked++
        return torrent
    }

    override fun folder(): Path? {
        folderAsked++
        return folder
    }
}
