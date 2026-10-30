package dev.anydm.engine.e2eharness

import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.partialPath
import dev.anydm.engine.core.targetPath
import dev.anydm.engine.e2e.spoilUnsynced
import dev.anydm.engine.platform.deleteRecursively
import dev.anydm.engine.platform.newTempFolder
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.NewFile
import dev.anydm.engine.store.NewSegment
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.openDatabaseFile
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import java.io.File
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test

/** The end-to-end tests' power cut (#576): bytes above the stored watermarks are spoiled, nothing below them. */
class PowerCutTest {
    private val root = newTempFolder("anydm-power-cut-")
    private val database = Path(root, "anydm.db")

    @AfterTest
    fun cleanUp() = deleteRecursively(root)

    @Test
    fun onlyBytesAboveTheWatermarksChange() =
        runBlocking<Unit> {
            val store = Store(openDatabaseFile(database), Dispatchers.IO)
            val download = store.downloads.add(NewDownload(DownloadKind.DIRECT, saveFolder = root.toString()))
            val file = store.downloads.replaceFiles(download.id, listOf(NewFile(0, "f.bin", 100))).single()
            val (first, second) = store.downloads.replaceSegments(file.id, listOf(NewSegment(0, 50), NewSegment(50, 100)))
            store.downloads.saveWatermarks(mapOf(first.id to 20L, second.id to 70L))
            store.close()
            val partial = File(partialPath(targetPath(root, "f.bin"), download.id).toString())
            partial.writeBytes(ByteArray(100) { 0x11 })

            spoilUnsynced(database, root, Random(1))

            val bytes = partial.readBytes()
            (bytes.slice(0 until 20) + bytes.slice(50 until 70)).all { it == 0x11.toByte() } shouldBe true
            bytes.slice(20 until 50).all { it == 0x11.toByte() } shouldBe false
            bytes.slice(70 until 100).all { it == 0x11.toByte() } shouldBe false
        }

    @Test
    fun nothingStoredIsNothingToSpoil() =
        runBlocking<Unit> {
            val store = Store(openDatabaseFile(database), Dispatchers.IO)
            store.downloads.add(NewDownload(DownloadKind.DIRECT, saveFolder = root.toString()))
            store.close()

            spoilUnsynced(database, root, Random(1))
        }
}
