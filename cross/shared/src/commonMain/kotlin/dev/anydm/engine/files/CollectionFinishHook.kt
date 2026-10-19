package dev.anydm.engine.files

import dev.anydm.engine.core.Download
import dev.anydm.engine.core.FinishHook
import dev.anydm.engine.core.targetPath
import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.platform.FileSystem
import dev.anydm.engine.platform.writeWhole
import dev.anydm.engine.store.CollectionItemStore
import dev.anydm.engine.store.DownloadStore
import kotlinx.coroutines.CancellationException
import kotlinx.io.files.Path

/**
 * When a job finishes, [delegate] hears of it first. If the job is an item of a collection (MED-16), the item is marked
 * done and, when [playlistFile] says so, the collection's `.m3u8` is written again from the items that are done, in
 * playlist order, listing only files that are still there (MED-17). Nothing here can fail the job.
 */
class CollectionFinishHook(
    private val items: CollectionItemStore,
    private val downloads: DownloadStore,
    private val fileSystem: FileSystem,
    private val playlistFile: suspend () -> Boolean,
    private val delegate: FinishHook,
) : FinishHook {
    override suspend fun finished(
        download: Download,
        outputs: List<Path>,
    ) {
        delegate.finished(download, outputs)
        val collection = download.parentId ?: return
        try {
            if (items.markDone(download.id) == null) return
            if (playlistFile()) writePlaylist(collection)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The playlist file is a convenience: the job is finished whether or not it could be written.
        }
    }

    private suspend fun writePlaylist(collectionId: dev.anydm.engine.core.DownloadId) {
        val collection = downloads.get(collectionId) ?: return
        val folder = collection.saveFolder?.let(::Path) ?: return
        val entries =
            items.items(collectionId).filter { it.done }.mapNotNull { item ->
                val child = item.childId?.let { downloads.get(it) } ?: return@mapNotNull null
                val placed = downloads.files(child.id).firstNotNullOfOrNull { it.placedPath } ?: return@mapNotNull null
                val childFolder = child.saveFolder?.let(::Path) ?: folder
                val full = targetPath(childFolder, placed)
                if (fileSystem.size(full) !is FileResult.Ok) return@mapNotNull null
                PlaylistFileEntry(
                    title = item.title ?: placed.substringAfterLast('/').substringBeforeLast('.'),
                    durationSeconds = item.duration,
                    path = if (childFolder == folder) placed else full.toString(),
                )
            }
        val target = Path(folder, "${folder.name}.m3u8")
        val temp = Path(folder, ".${folder.name}.m3u8.tmp")
        if (fileSystem.writeWhole(temp, PlaylistFile.render(entries).encodeToByteArray()) !is FileResult.Ok ||
            fileSystem.atomicMove(temp, target, replace = true) !is FileResult.Ok
        ) {
            fileSystem.delete(temp)
        }
    }
}
