package dev.anydm.engine.files

import dev.anydm.engine.core.CollectionKind
import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.FinishHook
import dev.anydm.engine.platform.DesktopFileSystem
import dev.anydm.engine.platform.FakeFileSystem
import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.NewFile
import dev.anydm.engine.store.NewItem
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.openInMemoryDatabase
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.Test

class CollectionFinishHookTest {
    private val store = Store(openInMemoryDatabase(), Dispatchers.IO)
    private val fs = FakeFileSystem()
    private val folder = "/dl/My List"
    private var enabled = true

    private class Recording : FinishHook {
        val seen = mutableListOf<DownloadId>()

        override suspend fun finished(
            download: Download,
            outputs: List<Path>,
        ) {
            seen += download.id
        }
    }

    private val delegate = Recording()
    private val hook = CollectionFinishHook(store.collectionItems, store.downloads, fs, { enabled }, delegate)
    private val playlist get() = Path(folder, "My List.m3u8")

    @AfterTest
    fun close() {
        store.close()
    }

    private suspend fun collection(count: Int = 2): Pair<Download, List<Download>> {
        fs.prepareFolder(Path(folder))
        return store.downloads.addCollection(
            NewDownload(DownloadKind.SITE, collection = CollectionKind.PLAYLIST, saveFolder = folder),
            (1..count).map {
                NewDownload(
                    DownloadKind.SITE,
                    mirrors = listOf("https://www.example.com/watch?v=v$it"),
                    saveFolder = folder,
                    playlistIndex = it,
                )
            },
            (1..count).map { NewItem("youtube", "v$it", it, "Video $it", 60 + it) },
        )
    }

    private suspend fun place(
        child: Download,
        name: String,
        inFolder: String = folder,
    ) {
        val file = store.downloads.replaceFiles(child.id, listOf(NewFile(0, name))).single()
        store.downloads.setPlacedPath(file.id, name)
        fs.put(Path(inFolder, name), ByteArray(3))
    }

    private fun text() = fs.read(playlist)?.decodeToString()

    @Test
    fun aFinishedChildIsMarkedDoneAndListed() =
        runBlocking<Unit> {
            val (collection, children) = collection()
            place(children[0], "one.mp4")

            hook.finished(children[0], listOf(Path(folder, "one.mp4")))

            store.collectionItems.items(collection.id).map { it.done } shouldBe listOf(true, false)
            text() shouldBe "#EXTM3U\n#EXTINF:61,Video 1\none.mp4\n"
        }

    @Test
    fun theDelegateStillRuns() =
        runBlocking<Unit> {
            val (_, children) = collection()
            place(children[0], "one.mp4")
            val loose = store.downloads.add(NewDownload(DownloadKind.DIRECT))

            hook.finished(children[0], emptyList())
            hook.finished(loose, emptyList())

            delegate.seen shouldBe listOf(children[0].id, loose.id)
        }

    @Test
    fun orderIsByPlaylistIndexNotByFinishTime() =
        runBlocking<Unit> {
            val (_, children) = collection()
            place(children[0], "one.mp4")
            place(children[1], "two.mp4")

            hook.finished(children[1], emptyList())
            hook.finished(children[0], emptyList())

            text() shouldBe "#EXTM3U\n#EXTINF:61,Video 1\none.mp4\n#EXTINF:62,Video 2\ntwo.mp4\n"
        }

    @Test
    fun aMissingOutputIsLeftOut() =
        runBlocking<Unit> {
            val (_, children) = collection()
            place(children[0], "one.mp4")
            place(children[1], "two.mp4")
            hook.finished(children[0], emptyList())
            fs.remove(Path(folder, "one.mp4"))

            hook.finished(children[1], emptyList())

            text() shouldBe "#EXTM3U\n#EXTINF:62,Video 2\ntwo.mp4\n"
        }

    @Test
    fun noFileWhenTheSettingIsOffButTheItemIsStillDone() =
        runBlocking<Unit> {
            enabled = false
            val (collection, children) = collection()
            place(children[0], "one.mp4")

            hook.finished(children[0], emptyList())

            fs.exists(playlist) shouldBe false
            store.collectionItems
                .items(collection.id)
                .first()
                .done shouldBe true
        }

    @Test
    fun aJobThatIsNotAnItemIsIgnored() =
        runBlocking<Unit> {
            val loose = store.downloads.add(NewDownload(DownloadKind.DIRECT, saveFolder = folder))
            fs.prepareFolder(Path(folder))

            hook.finished(loose, emptyList())

            fs.exists(playlist) shouldBe false
        }

    @Test
    fun aWriteFailureDoesNotThrowAndTheItemIsStillDone() =
        runBlocking<Unit> {
            val (collection, children) = collection()
            place(children[0], "one.mp4")
            fs.faults[FakeFileSystem.Op.PREALLOCATE] = { FileResult.IoError(it, "disk full") }

            hook.finished(children[0], emptyList())

            store.collectionItems
                .items(collection.id)
                .first()
                .done shouldBe true
            fs.exists(playlist) shouldBe false
        }

    @Test
    fun theFileIsReplacedAndNoTemporaryFileIsLeft() =
        runBlocking<Unit> {
            val (_, children) = collection()
            place(children[0], "one.mp4")
            place(children[1], "two.mp4")

            hook.finished(children[0], emptyList())
            hook.finished(children[1], emptyList())

            text()!!.lines().count { it.isNotEmpty() && !it.startsWith("#") } shouldBe 2
            fs.paths().filter { it.name.contains("m3u8") } shouldBe listOf(playlist)
        }

    @Test
    fun aChildInAnotherFolderIsListedByItsFullPath() =
        runBlocking<Unit> {
            val (collection, _) = collection(count = 1)
            val elsewhere =
                store.downloads
                    .addChildren(
                        collection.id,
                        listOf(
                            NewDownload(DownloadKind.SITE, mirrors = listOf("https://www.example.com/watch?v=x"), saveFolder = "/other"),
                        ),
                        listOf(NewItem("youtube", "x", 9, "Elsewhere", 5)),
                    ).single()
            place(elsewhere, "x.mp4", inFolder = "/other")

            hook.finished(elsewhere, emptyList())

            text() shouldBe "#EXTM3U\n#EXTINF:5,Elsewhere\n/other/x.mp4\n"
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun manyChildrenFinishingAtOnceAllEndUpInTheFile() =
        runBlocking<Unit> {
            val real = Store(openInMemoryDatabase(), Dispatchers.IO.limitedParallelism(1))
            val dir =
                java.nio.file.Files
                    .createTempDirectory("anydm-hook-")
                    .toRealPath()
                    .toFile()
            try {
                // Many rounds: the failure needs two writers to interleave, which one round only sometimes does.
                repeat(25) { round -> finishAllAtOnce(real, dir, "My List $round") }
            } finally {
                real.close()
                dir.deleteRecursively()
            }
        }

    private suspend fun finishAllAtOnce(
        real: Store,
        dir: java.io.File,
        name: String,
    ) {
        val where =
            java.io
                .File(dir, name)
                .also { it.mkdirs() }
                .toString()
        val count = 16
        val (collection, children) =
            real.downloads.addCollection(
                NewDownload(DownloadKind.SITE, collection = CollectionKind.PLAYLIST, saveFolder = where),
                (1..count).map {
                    NewDownload(
                        DownloadKind.SITE,
                        mirrors = listOf("https://www.example.com/watch?v=$name$it"),
                        saveFolder = where,
                        playlistIndex = it,
                    )
                },
                (1..count).map { NewItem("youtube", "$name$it", it, "Video $it", 60 + it) },
            )
        children.forEachIndexed { i, child ->
            val file = real.downloads.replaceFiles(child.id, listOf(NewFile(0, "v${i + 1}.mp4"))).single()
            real.downloads.setPlacedPath(file.id, "v${i + 1}.mp4")
            java.io.File(where, "v${i + 1}.mp4").writeBytes(ByteArray(3))
        }
        val concurrent = CollectionFinishHook(real.collectionItems, real.downloads, DesktopFileSystem(), { true }, Recording())

        coroutineScope {
            children.map { child -> async(Dispatchers.Default) { concurrent.finished(child, emptyList()) } }.awaitAll()
        }

        java.io
            .File(where, "$name.m3u8")
            .readLines()
            .filter { !it.startsWith("#") } shouldBe (1..count).map { "v$it.mp4" }
        real.collectionItems.items(collection.id).all { it.done } shouldBe true
        java.io
            .File(where)
            .list()
            .orEmpty()
            .filter { it.contains("tmp") } shouldBe emptyList()
    }

    @Test
    fun aDelegateThatThrowsDoesNotStopTheItemBeingRecorded() =
        runBlocking<Unit> {
            val (collection, children) = collection()
            place(children[0], "one.mp4")
            val failing =
                CollectionFinishHook(
                    store.collectionItems,
                    store.downloads,
                    fs,
                    { true },
                    object : FinishHook {
                        override suspend fun finished(
                            download: Download,
                            outputs: List<Path>,
                        ) = throw IllegalStateException("history is broken")
                    },
                )

            runCatching { failing.finished(children[0], emptyList()) }.exceptionOrNull()?.message shouldBe "history is broken"

            store.collectionItems
                .items(collection.id)
                .first()
                .done shouldBe true
            text() shouldBe "#EXTM3U\n#EXTINF:61,Video 1\none.mp4\n"
        }
}
