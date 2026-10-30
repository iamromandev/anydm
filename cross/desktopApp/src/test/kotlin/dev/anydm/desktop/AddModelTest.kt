package dev.anydm.desktop

import dev.anydm.engine.AddTorrentResult
import dev.anydm.engine.InspectResult
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DuplicateFound
import dev.anydm.engine.core.DuplicateReason
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.ItemSelection
import dev.anydm.engine.os.LinkProbeResult
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.torrent.TorrentFile
import dev.anydm.engine.torrent.TorrentInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AddModelTest {
    private val source = FakeSource()
    private var done = 0

    private fun TestScope.model(initial: List<String> = emptyList()) =
        AddModel(
            source,
            backgroundScope + UnconfinedTestDispatcher(testScheduler),
            initial,
            onDone = { done++ },
            now = { Instant.parse("2026-10-30T12:00:00Z") },
            zone = TimeZone.UTC,
            isFolder = { it == "/dl" },
            settle = Duration.ZERO,
        )

    private fun AddModel.statuses() = state.value.rows.map { it.status }

    @Test
    fun `each link is read and shows what it is`() =
        runTest {
            source.probes["https://a.example/gone.zip"] = LinkProbeResult.Failed(StepResult.Failed(ErrorClass.GONE, "HTTP 404"))
            val model = model()
            model.setText("https://a.example/x.zip\nhello\nhttps://a.example/gone.zip\nhttps://a.example/a[3-1].bin")
            assertEquals(listOf(RowStatus.READY, RowStatus.NOT_A_LINK, RowStatus.FAILED, RowStatus.INVALID), model.statuses())
            assertEquals(
                "x.zip",
                model.state.value.rows[0]
                    .name,
            )
            assertEquals(
                "HTTP 404",
                model.state.value.rows[2]
                    .message,
            )
        }

    @Test
    fun `a link already read is not read again`() =
        runTest {
            val model = model()
            model.setText("https://a.example/x.zip")
            model.setText("https://a.example/x.zip\nhttps://a.example/y.zip")
            assertEquals(listOf("https://a.example/x.zip", "https://a.example/y.zip"), source.probed)
        }

    @Test
    fun `a link still being read shows as checking`() =
        runTest {
            source.gate = CompletableDeferred()
            val model = model()
            model.setText("https://a.example/x.zip")
            assertEquals(listOf(RowStatus.CHECKING), model.statuses())
            assertFalse(model.state.value.canAdd)
            source.gate!!.complete(Unit)
            assertEquals(listOf(RowStatus.READY), model.statuses())
        }

    @Test
    fun `the links it was opened with are read at once`() =
        runTest {
            val model = model(listOf("https://a.example/x.zip", "https://a.example/y.zip"))
            assertEquals(listOf(RowStatus.READY, RowStatus.READY), model.statuses())
        }

    @Test
    fun `a kind this computer can't take is unavailable`() =
        runTest {
            source.available = setOf(DownloadKind.DIRECT)
            val model = model()
            model.setText("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567\nhttps://a.example/x.zip")
            assertEquals(listOf(RowStatus.UNAVAILABLE, RowStatus.READY), model.statuses())
            assertTrue(model.state.value.canAdd)
        }

    @Test
    fun `a duplicate is skipped unless added anyway`() =
        runTest {
            source.probes["https://a.example/x.zip"] =
                LinkProbeResult.Probed(
                    "https://a.example/x.zip",
                    DownloadKind.DIRECT,
                    "x.zip",
                    1,
                    true,
                    true,
                    DuplicateFound(DownloadId(12), DuplicateReason.SAME_URL),
                )
            val model = model()
            model.setText("https://a.example/x.zip\nhttps://a.example/y.zip")
            val row = model.state.value.rows[0]
            assertEquals(RowStatus.DUPLICATE, row.status)
            assertEquals(DownloadId(12), row.existing)

            model.add()
            assertEquals(listOf("https://a.example/y.zip"), source.added.map { it.mirrors.single() })

            model.setAddAnyway("https://a.example/x.zip", true)
            model.add()
            assertEquals(true, source.added.last().allowDuplicate)
            assertEquals(
                "https://a.example/x.zip",
                source.added
                    .last()
                    .mirrors
                    .single(),
            )
        }

    @Test
    fun `the options go with every link`() =
        runTest {
            val model = model()
            model.setText("https://a.example/x.zip")
            model.setOptions { it.copy(folder = "/dl", start = StartChoice.AT, startAt = "2026-10-31 08:00") }
            model.add()
            val added = source.added.single()
            assertEquals("/dl", added.saveFolder)
            assertEquals(Instant.parse("2026-10-31T08:00:00Z"), added.startAt)
            assertEquals(DownloadKind.DIRECT, added.kind)
        }

    @Test
    fun `errors in the options keep add off`() =
        runTest {
            val model = model()
            model.setText("https://a.example/x.zip")
            model.setOptions { it.copy(folder = "/nowhere") }
            assertTrue(Field.FOLDER in model.state.value.errors)
            assertFalse(model.state.value.canAdd)
            model.add()
            assertTrue(source.added.isEmpty())
        }

    @Test
    fun `start paused pauses each download once added`() =
        runTest {
            val model = model()
            model.setText("https://a.example/x.zip")
            model.setOptions { it.copy(start = StartChoice.PAUSED) }
            model.add()
            assertEquals(listOf("add https://a.example/x.zip", "pause 101"), source.calls)
        }

    @Test
    fun `media pages go to yt-dlp with the chosen format, unread`() =
        runTest {
            val model = model()
            model.setText("https://video.example/watch?v=1")
            model.setOptions { it.copy(mode = AddMode.MEDIA, preset = MediaPreset.P720, subtitles = "en", sponsor = SponsorChoice.CUT) }
            assertEquals(listOf(RowStatus.MEDIA), model.statuses())
            assertTrue(source.probed.isEmpty() || source.probed == listOf("https://video.example/watch?v=1"))
            model.add()
            val added = source.added.single()
            assertEquals(DownloadKind.SITE, added.kind)
            assertEquals(720, added.format?.height)
            assertEquals(listOf("en"), added.format?.subtitles?.languages)
        }

    @Test
    fun `a playlist is added as a collection with its range`() =
        runTest {
            val model = model()
            model.setText("https://video.example/list")
            model.setOptions { it.copy(mode = AddMode.MEDIA, playlist = true, range = "1:20", limit = "10") }
            model.add()
            val (url, selection, template) = source.playlistsAdded.single()
            assertEquals("https://video.example/list", url)
            assertEquals(ItemSelection("1:20", 10), selection)
            assertEquals(DownloadKind.SITE, template.kind)
        }

    private val info =
        TorrentInfo(
            "pack",
            "0123456789abcdef0123456789abcdef01234567",
            listOf(TorrentFile(0, "pack/a.bin", 10), TorrentFile(1, "pack/b.bin", 20), TorrentFile(2, "pack/c.bin", 30)),
            byteArrayOf(1),
        )
    private val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"

    @Test
    fun `one torrent is read for its files, and the chosen ones are added`() =
        runTest {
            source.inspects[magnet] = InspectResult.Inspected(info)
            val model = model()
            model.setText(magnet)
            val pick = model.state.value.torrent!!
            assertEquals(setOf("pack/a.bin", "pack/b.bin", "pack/c.bin"), pick.selected)
            assertEquals(
                "pack",
                model.state.value.rows
                    .single()
                    .name,
            )
            model.toggleTorrentFile("pack/b.bin")
            model.setSequential(true)
            model.add()
            val added = source.torrentsAdded.single()
            assertEquals(setOf("pack/a.bin", "pack/c.bin"), added.files)
            assertTrue(added.sequential)
        }

    @Test
    fun `a torrent with no file chosen can't be added`() =
        runTest {
            source.inspects[magnet] = InspectResult.Inspected(info.let { TorrentInfo(it.name, it.infoHash, it.files.take(1), it.metadata) })
            val model = model()
            model.setText(magnet)
            model.toggleTorrentFile("pack/a.bin")
            assertFalse(model.state.value.canAdd)
        }

    @Test
    fun `an ftp login goes into the address the engine takes`() =
        runTest {
            val model = model()
            model.setText("sftp://host.example/f.iso")
            assertTrue(model.state.value.needsLogin)
            model.setOptions { it.copy(user = "me", password = "s3cret") }
            model.add()
            assertEquals(
                "sftp://me:s3cret@host.example/f.iso",
                source.added
                    .single()
                    .mirrors
                    .single(),
            )
            assertEquals(DownloadKind.FTP, source.added.single().kind)
        }

    @Test
    fun `an sftp key login is saved first, and neither the read nor the address carries a login`() =
        runTest {
            val model = model()
            model.setText("sftp://host.example/f.iso")
            model.setOptions { it.copy(user = "me", keyPath = "/keys/id_ed25519") }
            assertEquals(listOf(RowStatus.READY), model.statuses())
            model.add()
            assertEquals(listOf("saveKeyLogin sftp://host.example/f.iso", "add sftp://host.example/f.iso"), source.calls)
            assertEquals(
                "/keys/id_ed25519",
                source.keyLogins
                    .single()
                    .second.keyPath,
            )
            assertEquals(
                "me",
                source.keyLogins
                    .single()
                    .second.user,
            )
        }

    @Test
    fun `a login is never sent with a read, only with the add`() =
        runTest {
            val model = model()
            model.setText("sftp://host.example/f.iso")
            model.setOptions { it.copy(user = "me", password = "s3cret") }
            assertEquals(listOf("sftp://host.example/f.iso"), source.probed)
            assertEquals(listOf(RowStatus.READY), model.statuses())
        }

    @Test
    fun `one login for two servers is refused`() =
        runTest {
            val model = model()
            model.setText("ftp://a.example/f\nftp://b.example/f")
            model.setOptions { it.copy(user = "me", password = "x") }
            assertTrue(Field.LOGIN in model.state.value.errors)
            assertFalse(model.state.value.canAdd)
        }

    @Test
    fun `a duplicate found at the add can still be added anyway`() =
        runTest {
            source.addResults["https://a.example/x.zip"] = CommandResult.Duplicate(DuplicateFound(DownloadId(5), DuplicateReason.SAME_FILE))
            val model = model()
            model.setText("https://a.example/x.zip")
            model.add()
            assertEquals(
                RowStatus.DUPLICATE,
                model.state.value.rows
                    .single()
                    .status,
            )
            model.setAddAnyway("https://a.example/x.zip", true)
            source.addResults.clear()
            assertTrue(model.state.value.canAdd)
            model.add()
            assertEquals(true, source.added.last().allowDuplicate)
            assertEquals(
                RowStatus.ADDED,
                model.state.value.rows
                    .single()
                    .status,
            )
        }

    @Test
    fun `a failed add is tried again by the next add`() =
        runTest {
            source.addResults["https://a.example/x.zip"] = CommandResult.Invalid("busy")
            val model = model()
            model.setText("https://a.example/x.zip")
            model.add()
            assertEquals(
                RowStatus.ADD_FAILED,
                model.state.value.rows
                    .single()
                    .status,
            )
            source.addResults.clear()
            model.add()
            assertEquals(
                RowStatus.ADDED,
                model.state.value.rows
                    .single()
                    .status,
            )
        }

    @Test
    fun `an engine error shows on its row`() =
        runTest {
            source.addThrows = RuntimeException("database is locked")
            val model = model()
            model.setText("https://a.example/x.zip")
            model.add()
            val row =
                model.state.value.rows
                    .single()
            assertEquals(RowStatus.ADD_FAILED, row.status)
            assertEquals("It could not be added: database is locked", row.message)
        }

    @Test
    fun `each torrent is added with what was read of it`() =
        runTest {
            val other = "magnet:?xt=urn:btih:89abcdef0123456789abcdef0123456789abcdef"
            source.inspects[magnet] = InspectResult.Inspected(info)
            source.inspects[other] = InspectResult.Inspected(info)
            val model = model()
            model.setText("$magnet\n$other")
            model.add()
            assertEquals(2, source.torrentsAdded.size)
            assertTrue(source.torrentsAdded.all { it.info != null })
        }

    @Test
    fun `a link being typed is read once it settles`() =
        runTest {
            val model =
                AddModel(
                    source,
                    backgroundScope + UnconfinedTestDispatcher(testScheduler),
                    now = { Instant.parse("2026-10-30T12:00:00Z") },
                    zone = TimeZone.UTC,
                    settle = 400.milliseconds,
                )
            model.setText("https://a.example/x")
            advanceTimeBy(100)
            model.setText("https://a.example/x.zip")
            advanceTimeBy(500)
            assertEquals(listOf("https://a.example/x.zip"), source.probed)
            assertEquals(listOf(RowStatus.READY), model.statuses())
        }

    @Test
    fun `each row shows what its add came to, and the dialog closes when all were added`() =
        runTest {
            source.addResults["https://a.example/y.zip"] = CommandResult.Invalid("no room")
            val model = model()
            model.setText("https://a.example/x.zip\nhttps://a.example/y.zip")
            model.add()
            assertEquals(listOf(RowStatus.ADDED, RowStatus.ADD_FAILED), model.statuses())
            assertEquals(
                "no room",
                model.state.value.rows[1]
                    .message,
            )
            assertEquals(0, done)

            val all = model()
            all.setText("https://a.example/z.zip")
            all.add()
            assertEquals(1, done)
        }

    @Test
    fun `a stopped engine stops the add`() =
        runTest {
            val model = model()
            model.setText("https://a.example/x.zip\nhttps://a.example/y.zip")
            source.refuseAll = true
            model.add()
            assertEquals(
                RowStatus.ADD_FAILED,
                model.state.value.rows[0]
                    .status,
            )
            assertEquals(
                "The download engine has stopped.",
                model.state.value.rows[0]
                    .message,
            )
            assertNull(
                model.state.value.rows[1]
                    .message,
            )
            assertEquals(0, done)
        }

    @Test
    fun `a torrent tried again keeps the files chosen and the order`() =
        runTest {
            source.inspects[magnet] = InspectResult.Inspected(info)
            source.torrentResult = AddTorrentResult.Failed(StepResult.Failed(ErrorClass.TRANSIENT, "tracker down"))
            val model = model()
            model.setText(magnet)
            model.toggleTorrentFile("pack/b.bin")
            model.setSequential(true)
            model.add()
            assertEquals(
                RowStatus.ADD_FAILED,
                model.state.value.rows
                    .single()
                    .status,
            )
            assertTrue(model.state.value.torrent != null)

            source.torrentResult = null
            model.add()
            val retried = source.torrentsAdded.last()
            assertEquals(setOf("pack/a.bin", "pack/c.bin"), retried.files)
            assertTrue(retried.sequential)
        }

    @Test
    fun `a row tried again follows the options as they are now`() =
        runTest {
            source.addResults["https://a.example/x.zip"] = CommandResult.Invalid("no extractor")
            val model = model()
            model.setText("https://a.example/x.zip")
            model.setOptions { it.copy(mode = AddMode.MEDIA) }
            model.add()
            assertEquals(DownloadKind.SITE, source.added.last().kind)
            source.addResults.clear()
            model.setOptions { it.copy(mode = AddMode.FILE) }
            model.add()
            assertEquals(DownloadKind.DIRECT, source.added.last().kind)
        }

    @Test
    fun `a key that can't be kept stops that add`() =
        runTest {
            source.keySaved = false
            val model = model()
            model.setText("sftp://host.example/f.iso")
            model.setOptions { it.copy(user = "me", keyPath = "/keys/id_ed25519") }
            model.add()
            assertTrue(source.added.isEmpty())
            assertEquals(
                RowStatus.ADD_FAILED,
                model.state.value.rows
                    .single()
                    .status,
            )
        }

    @Test
    fun `a download that was added stays added when its pause fails`() =
        runTest {
            source.pauseThrows = IllegalStateException("the engine has stopped")
            val model = model()
            model.setText("https://a.example/x.zip")
            model.setOptions { it.copy(start = StartChoice.PAUSED) }
            model.add()
            val row =
                model.state.value.rows
                    .single()
            assertEquals(RowStatus.ADDED, row.status)
            assertEquals("Added, but not paused.", row.message)
        }

    @Test
    fun `clearing the text while adding doesn't close the dialog`() =
        runTest {
            source.gate = CompletableDeferred()
            val model = model()
            model.setText("https://a.example/x.zip")
            source.gate!!.complete(Unit)
            source.gate = CompletableDeferred()
            model.add()
            model.setText("")
            source.gate!!.complete(Unit)
            assertEquals(0, done)
        }
}
