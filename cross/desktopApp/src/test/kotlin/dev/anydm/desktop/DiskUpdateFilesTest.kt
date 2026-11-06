package dev.anydm.desktop

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A dispatcher that runs nothing until [runQueued] is called. */
private class ManualDispatcher : CoroutineDispatcher() {
    private val queue = ArrayDeque<Runnable>()

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        queue.addLast(block)
    }

    fun runQueued() {
        while (queue.isNotEmpty()) queue.removeFirst().run()
    }
}

internal class DiskUpdateFilesTest {
    private fun withRoot(block: suspend (Path, DiskUpdateFiles) -> Unit) =
        runTest {
            val root = Files.createTempDirectory("anydm-update-test")
            try {
                block(root, DiskUpdateFiles(root))
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun `each download gets a new empty folder under the root`() =
        withRoot { root, files ->
            val a = files.createDirectory()
            val b = files.createDirectory()

            assertEquals(root, a.parent)
            assertNotEquals(a, b)
            assertTrue(Files.list(a).use { it.count() } == 0L)
        }

    @Test
    fun `a file is written, hashed and removed with its folder`() =
        withRoot { _, files ->
            val directory = files.createDirectory()
            val sink = files.create(directory, "anydm-1.2.0.dmg")
            sink.write("abc".toByteArray() + ByteArray(5), 3)
            sink.close()
            val file = directory.resolve("anydm-1.2.0.dmg")

            // SHA-256 of "abc".
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", files.sha256(file))
            files.deleteAll(directory)
            assertFalse(Files.exists(directory))
            assertNull(files.sha256(file))
        }

    @Test
    fun `a name that leaves the folder is refused`() =
        withRoot { root, files ->
            val directory = files.createDirectory()

            for (name in listOf("../evil.dmg", "a/b.dmg", "..", "/etc/passwd")) {
                assertFailsWith<IllegalArgumentException>(name) { files.create(directory, name) }
            }
            assertFalse(Files.exists(root.resolve("evil.dmg")))
        }

    @Test
    fun `a file is never overwritten`() =
        withRoot { _, files ->
            val directory = files.createDirectory()
            files.create(directory, "a.dmg").close()

            assertFailsWith<IOException> { files.create(directory, "a.dmg") }
        }

    @Test
    fun `only folders it made are written to or removed`() =
        withRoot { root, files ->
            val foreign = Files.createDirectory(root.resolve("not-ours"))
            Files.writeString(foreign.resolve("keep.txt"), "x")

            files.deleteAll(foreign)
            assertTrue(Files.exists(foreign.resolve("keep.txt")))
            assertFailsWith<IllegalArgumentException> { files.create(foreign, "a.dmg") }
        }

    @Test
    fun `a link is not hashed as a file`() =
        withRoot { root, files ->
            val target = root.resolve("target.bin")
            Files.writeString(target, "abc")
            val link = root.resolve("link.bin")
            try {
                Files.createSymbolicLink(link, target)
            } catch (e: UnsupportedOperationException) {
                return@withRoot
            }

            assertNull(files.sha256(link))
        }

    @Test
    fun `a download folder can be used by its owner alone`() =
        withRoot { _, files ->
            val directory = files.createDirectory()
            val view = Files.getFileAttributeView(directory, java.nio.file.attribute.PosixFileAttributeView::class.java) ?: return@withRoot

            assertEquals("rwx------", PosixFilePermissions.toString(view.readAttributes().permissions()))
        }

    private fun aged(
        path: Path,
        days: Long,
    ): Path =
        path.also { Files.setLastModifiedTime(it, FileTime.from(Instant.now().minus(days, ChronoUnit.DAYS).minus(1, ChronoUnit.HOURS))) }

    @Test
    fun `old download folders of the user are swept and young or foreign ones are not`() =
        withRoot { root, files ->
            val old = aged(Files.createDirectory(root.resolve("anydm-update-old")), 2)
            Files.writeString(old.resolve("installer.dmg"), "x")
            Files.createDirectory(old.resolve("sub")).resolve("deep.bin").let { Files.writeString(it, "y") }
            aged(old, 2)
            val young = Files.createDirectory(root.resolve("anydm-update-young"))
            val almost = Files.createDirectory(root.resolve("anydm-update-almost"))
            Files.setLastModifiedTime(almost, FileTime.from(Instant.now().minus(20, ChronoUnit.HOURS)))
            val other = aged(Files.createDirectory(root.resolve("something-else")), 30)
            val file = aged(Files.writeString(root.resolve("anydm-update-file"), "z"), 30)

            files.sweepOld()

            assertFalse(Files.exists(old))
            assertTrue(Files.exists(young))
            assertTrue(Files.exists(almost))
            assertTrue(Files.exists(other))
            assertTrue(Files.exists(file))
        }

    private class Someone(
        private val label: String,
    ) : java.nio.file.attribute.UserPrincipal {
        override fun getName() = label
    }

    @Test
    fun `an old folder owned by another principal is not swept`() =
        runTest {
            val root = Files.createTempDirectory("anydm-update-test")
            try {
                val foreign = Files.createDirectory(root.resolve("anydm-update-foreign"))
                Files.writeString(foreign.resolve("installer.dmg"), "x")
                aged(foreign, 5)

                DiskUpdateFiles(root, owner = { Someone("somebody-else") }).sweepOld()

                assertTrue(Files.exists(foreign.resolve("installer.dmg")))
                // The same folder is swept once the caller is its owner.
                DiskUpdateFiles(root, owner = { Files.getOwner(foreign) }).sweepOld()
                assertFalse(Files.exists(foreign))
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun `sweeping with an unknown or failing user lookup removes nothing and fails nowhere`() =
        runTest {
            val root = Files.createTempDirectory("anydm-update-test")
            try {
                val old = aged(Files.createDirectory(root.resolve("anydm-update-old")), 5)

                DiskUpdateFiles(root, owner = { null }).sweepOld()
                DiskUpdateFiles(root, owner = { error("no lookup service") }).sweepOld()

                assertTrue(Files.exists(old))
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun `a folder this run is still using is not swept however old`() =
        withRoot { _, files ->
            val mine = aged(files.createDirectory(), 5)

            files.sweepOld()

            assertTrue(Files.exists(mine))
        }

    @Test
    fun `sweeping does not follow a link to somewhere else`() =
        withRoot { root, files ->
            val outside = Files.createDirectory(root.resolve("outside"))
            Files.writeString(outside.resolve("precious.txt"), "keep")
            aged(outside, 30)
            val link = root.resolve("anydm-update-link")
            try {
                Files.createSymbolicLink(link, outside)
            } catch (e: UnsupportedOperationException) {
                return@withRoot
            }

            files.sweepOld()

            assertTrue(Files.exists(outside.resolve("precious.txt")))
        }

    @Test
    fun `sweeping a missing folder is no error`() =
        runTest {
            val root = Files.createTempDirectory("anydm-update-test")
            val gone = root.resolve("nope")
            root.toFile().deleteRecursively()

            DiskUpdateFiles(gone).sweepOld()
        }

    @Test
    fun `removing a folder does not follow a link inside it`() =
        withRoot { root, files ->
            val outside = Files.createDirectory(root.resolve("outside"))
            Files.writeString(outside.resolve("precious.txt"), "keep")
            val directory = files.createDirectory()
            Files.writeString(directory.resolve("a.dmg"), "x")
            try {
                Files.createSymbolicLink(directory.resolve("link"), outside)
            } catch (e: UnsupportedOperationException) {
                return@withRoot
            }

            files.deleteAll(directory)

            assertFalse(Files.exists(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            assertTrue(Files.exists(outside.resolve("precious.txt")))
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a folder made for a caller that was cancelled meanwhile does not stay behind`() =
        runTest {
            val root = Files.createTempDirectory("anydm-update-test")
            try {
                val disk = ManualDispatcher()
                val files = DiskUpdateFiles(root, disk)
                val caller = launch(start = CoroutineStart.UNDISPATCHED) { files.createDirectory() }

                // The folder is made, then the caller is cancelled before it can take the answer.
                disk.runQueued()
                assertEquals(1L, Files.list(root).use { it.count() })
                caller.cancel()
                runCurrent()

                assertEquals(0L, Files.list(root).use { it.count() })
            } finally {
                root.toFile().deleteRecursively()
            }
        }
}
