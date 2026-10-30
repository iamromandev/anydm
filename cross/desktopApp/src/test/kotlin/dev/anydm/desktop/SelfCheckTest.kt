package dev.anydm.desktop

import dev.anydm.engine.EngineState
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class SelfCheckTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val folder = Files.createTempDirectory("self-check")
    private val source = FakeSource().also { it.state.value = EngineState.Running }

    @AfterTest
    fun close() {
        scope.cancel()
        folder.toFile().deleteRecursively()
    }

    // Plays the engine: once the check adds its download, it ends as [status] with [content] in the file.
    private fun finishWith(
        status: DownloadStatus,
        content: ByteArray? = SelfCheck.PAYLOAD,
    ) = scope.launch {
        while (source.added.isEmpty()) delay(10)
        val new = source.added.single()
        if (content != null) File(new.saveFolder!!, new.fileName!!).writeBytes(content)
        source.downloads.value = listOf(snapshot(101, status, name = new.fileName))
    }

    private fun check(timeoutMillis: Long = 5_000) = runBlocking { SelfCheck(source, folder, timeoutMillis).run() }

    @Test
    fun `a healthy engine downloads the file from its own server and passes`() {
        finishWith(DownloadStatus.COMPLETED)
        assertNull(check())
        val new = source.added.single()
        assertEquals(DownloadKind.DIRECT, new.kind)
        assertTrue(new.mirrors.single().startsWith("http://127.0.0.1:"), new.mirrors.toString())
        assertEquals(folder.toString(), new.saveFolder)
    }

    @Test
    fun `a failed engine is reported with its error`() {
        source.state.value = EngineState.Failed(IllegalStateException("no database"))
        assertTrue(check()!!.contains("no database"))
        assertTrue(source.added.isEmpty())
    }

    @Test
    fun `a build without torrents is reported`() {
        source.available = setOf(DownloadKind.DIRECT)
        assertTrue(check()!!.contains("torrent"))
    }

    @Test
    fun `a failed download is reported`() {
        finishWith(DownloadStatus.FAILED, content = null)
        assertTrue(check()!!.contains("FAILED"))
    }

    @Test
    fun `a file with other content is reported`() {
        finishWith(DownloadStatus.COMPLETED, content = byteArrayOf(1, 2, 3))
        assertTrue(check()!!.contains("differs"))
    }

    @Test
    fun `a download that never ends is reported`() {
        assertTrue(check(timeoutMillis = 300)!!.contains("did not finish"))
    }

    @Test
    fun `a file left by an earlier run does not count`() {
        File(folder.toFile(), SelfCheck.NAME).writeBytes(SelfCheck.PAYLOAD)
        finishWith(DownloadStatus.COMPLETED, content = null)
        assertTrue(check()!!.contains("missing"))
    }

    @Test
    fun `its data, logs and cache are all under the folder it was given`() {
        val dirs = SelfCheck.dirs(folder)
        listOf(dirs.data, dirs.logs, dirs.cache).forEach { assertTrue(it.toString().startsWith(folder.toString()), it.toString()) }
    }
}
