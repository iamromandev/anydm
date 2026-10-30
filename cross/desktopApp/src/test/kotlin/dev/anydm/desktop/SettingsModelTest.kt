package dev.anydm.desktop

import dev.anydm.engine.core.CategoryId
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.ProfileId
import dev.anydm.engine.core.ProxyId
import dev.anydm.engine.files.Category
import dev.anydm.engine.files.ClashPolicy
import dev.anydm.engine.files.Profile
import dev.anydm.engine.net.AfterQueueAction
import dev.anydm.engine.net.ProxyChoice
import dev.anydm.engine.net.ProxyEntry
import dev.anydm.engine.net.ProxyType
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.store.DayTraffic
import dev.anydm.engine.store.HistoryEntry
import dev.anydm.engine.store.ImportResult
import dev.anydm.engine.store.TransferSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.datetime.TimeZone
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class SettingsModelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val source = FakeSource()
    private val folders = mutableSetOf("/dl", "/v")
    private val now =
        object : Clock {
            override fun now() = Instant.parse("2026-10-30T12:00:00Z")
        }
    private val categoriesSeen = mutableListOf<List<Category>>()

    private fun model() =
        SettingsModel(
            source,
            scope,
            { it in folders },
            Dispatchers.Unconfined,
            now,
            TimeZone.UTC,
        ) { categoriesSeen += it }.also { it.load() }

    @AfterTest
    fun close() = scope.cancel()

    @Test
    fun `it shows the saved settings with nothing to save`() {
        source.transfer = TransferSettings(clashPolicy = ClashPolicy.SKIP)
        val state = model().state.value
        assertTrue(state.loaded)
        assertEquals(ClashPolicy.SKIP, state.draft.clash)
        assertFalse(state.dirty)
        assertFalse(state.canSave)
    }

    @Test
    fun `an edit is dirty until saved, and the engine gets it`() {
        val model = model()
        model.edit { it.copy(concurrency = "1", folder = "/dl") }
        assertTrue(model.state.value.canSave)

        model.save()

        assertEquals(1, source.transfer.concurrencyLimit)
        assertEquals("/dl", source.transfer.defaultSaveFolder)
        assertFalse(model.state.value.dirty)
        assertEquals("Saved.", model.state.value.message)
    }

    @Test
    fun `an invalid field keeps save off`() {
        val model = model()
        model.edit { it.copy(connections = "0") }
        assertTrue(SettingField.CONNECTIONS in model.state.value.errors)
        assertFalse(model.state.value.canSave)
        model.save()
        assertEquals(emptyList(), source.calls)
    }

    @Test
    fun `a refusal keeps the edits and says why`() {
        source.settingsResult = CommandResult.Invalid("no")
        val model = model()
        model.edit { it.copy(concurrency = "2") }
        model.save()
        assertTrue(model.state.value.dirty)
        assertEquals("Not saved: no.", model.state.value.message)
        assertFalse(model.state.value.busy)
    }

    @Test
    fun `revert brings back what is saved`() {
        val model = model()
        model.edit { it.copy(concurrency = "2") }
        model.revert()
        assertFalse(model.state.value.dirty)
    }

    @Test
    fun `the three transfer sections share the draft, leaving them asks first`() {
        val model = model()
        model.edit { it.copy(concurrency = "2") }
        model.select(Section.SPEED)
        assertEquals(Section.SPEED, model.state.value.section)
        assertTrue(model.state.value.dirty)

        model.select(Section.CATEGORIES)
        assertEquals(SettingsConfirm.Discard(Section.CATEGORIES), model.state.value.confirm)
        assertEquals(Section.SPEED, model.state.value.section)

        model.confirm()
        assertEquals(Section.CATEGORIES, model.state.value.section)
        assertFalse(model.state.value.dirty)
    }

    @Test
    fun `quit after the queue is kept by the app, not the engine`() {
        val model = model()
        model.edit { it.copy(afterQueue = AfterQueueChoice.QUIT) }
        model.save()
        assertTrue(model.quitAfterQueue.value)
        assertEquals(AfterQueueAction.NONE, source.transfer.afterQueue)
        assertEquals(AfterQueueChoice.QUIT, model.state.value.saved.afterQueue)
    }

    @Test
    fun `a category is added at the end, a taken name is refused`() {
        source.categoryList = listOf(Category(CategoryId(1), "Music", "/m", null, emptyList(), 0))
        val model = model()
        model.newCategory()
        model.changeCategory { it.copy(name = "music", folder = "/v") }
        model.saveCategory()
        assertEquals(
            "Another category has this name.",
            model.state.value.category
                ?.errors
                ?.get(CategoryField.NAME),
        )

        model.changeCategory { it.copy(name = "Videos", template = "{title}", extensions = "mkv") }
        model.saveCategory()
        assertNull(model.state.value.category)
        val videos = source.categoryList.last()
        assertEquals("Videos", videos.name)
        assertEquals(1, videos.priority)
        assertEquals(listOf("Music", "Videos"), categoriesSeen.last().map { it.name })
    }

    @Test
    fun `a bad template is caught before the engine sees it`() {
        val model = model()
        model.newCategory()
        model.changeCategory { it.copy(name = "V", folder = "/v", template = "{nope}") }
        model.saveCategory()
        assertTrue(
            CategoryField.TEMPLATE in
                model.state.value.category!!
                    .errors,
        )
        assertEquals(emptyList(), source.calls)
    }

    @Test
    fun `categories move up and down`() {
        source.categoryList =
            listOf(
                Category(CategoryId(1), "A", "/a", null, emptyList(), 0),
                Category(CategoryId(2), "B", "/b", null, emptyList(), 1),
            )
        val model = model()
        model.moveCategory(source.categoryList[1], up = true)
        assertEquals(
            listOf("B", "A"),
            model.state.value.categories
                .map { it.name },
        )
    }

    @Test
    fun `deleting a category asks first`() {
        val music = Category(CategoryId(1), "Music", "/m", null, emptyList(), 0)
        source.categoryList = listOf(music)
        val model = model()
        model.askDeleteCategory(music)
        assertEquals(SettingsConfirm.DeleteCategory(music), model.state.value.confirm)
        model.confirm()
        assertEquals(emptyList(), model.state.value.categories)
    }

    @Test
    fun `deleting the default profile leaves no default`() {
        val clips = Profile(ProfileId(5), "Clips", null, null, null, null, null)
        source.profileList = listOf(clips)
        source.defaultProfileId = clips.id
        val model = model()
        assertEquals(clips.id, model.state.value.defaultProfile)
        model.askDeleteProfile(clips)
        model.confirm()
        assertNull(model.state.value.defaultProfile)
    }

    @Test
    fun `a profile is added and made the default`() {
        val model = model()
        model.newProfile()
        model.changeProfile { it.copy(name = "Clips", media = ProfileMedia.P720, speedKbps = "100") }
        model.saveProfile()
        val clips = source.profileList.single()
        assertEquals(100L * 1024, clips.speedLimit)
        model.setDefaultProfile(clips.id)
        assertEquals(clips.id, model.state.value.defaultProfile)
    }

    @Test
    fun `a profile takes one of the saved proxies`() {
        val office = ProxyEntry(ProxyId(7), "Office", ProxyType.SOCKS5, "p.example", 1080, null, emptyList())
        source.proxyList = listOf(office)
        val model = model()
        assertEquals(listOf(office), model.state.value.proxies)
        model.newProfile()
        model.changeProfile { it.copy(name = "Work", proxy = ProxyChoice.Named(office.id)) }
        model.saveProfile()
        assertEquals(ProxyChoice.Named(office.id), source.profileList.single().proxy)
    }

    @Test
    fun `the profile editor sees a proxy added after the screen loaded`() {
        val model = model()
        assertEquals(emptyList(), model.state.value.proxies)
        source.proxyList = listOf(ProxyEntry(ProxyId(7), "Office", ProxyType.SOCKS5, "p.example", 1080, null, emptyList()))
        model.select(Section.PROFILES)
        assertEquals(source.proxyList, model.state.value.proxies)
    }

    @Test
    fun `history and the last thirty days of traffic show, and history clears`() {
        source.historyList =
            listOf(HistoryEntry(DownloadId(1), "a.zip", "/dl/a.zip", 10, Instant.parse("2026-10-29T10:00:00Z"), "https://h/a"))
        source.trafficList =
            listOf(
                DayTraffic("2026-10-29", "wifi", DownloadKind.DIRECT, 100, 1),
                DayTraffic("2026-10-29", "wifi", DownloadKind.TORRENT, 50, 20),
                DayTraffic("2026-10-30", "ethernet", DownloadKind.DIRECT, 7),
            )
        val model = model()
        model.select(Section.HISTORY)
        val state = model.state.value
        assertEquals("2026-10-01" to "2026-10-30", source.trafficAsked)
        assertEquals(1, state.history.size)
        assertEquals(listOf(DayTotal("2026-10-30", 7, 0), DayTotal("2026-10-29", 150, 21)), state.traffic)
        assertEquals(150L to 21L, state.networks["wifi"])

        model.askClearHistory()
        model.confirm()
        assertEquals(emptyList(), model.state.value.history)
    }

    @Test
    fun `reset keeps nothing it shouldn't and reloads`() {
        source.transfer = TransferSettings(clashPolicy = ClashPolicy.OVERWRITE, globalPause = true)
        val model = model()
        model.askReset()
        assertIs<SettingsConfirm.Reset>(model.state.value.confirm)
        model.confirm()
        assertEquals(ClashPolicy.RENAME, model.state.value.saved.clash)
        assertTrue(model.state.value.saved.globalPause)
    }

    @Test
    fun `export writes the file, import applies it or says what is wrong`() {
        val file = Files.createTempFile("anydm-settings", ".json")
        try {
            source.exported = """{"version": 1}"""
            val model = model()
            model.export(file)
            assertEquals("""{"version": 1}""", Files.readString(file))

            source.importResult = ImportResult.Refused(listOf("settings: connectionsPerDownload must be in 1..16"))
            model.import(file)
            assertEquals("Not imported: settings: connectionsPerDownload must be in 1..16.", model.state.value.message)

            source.importResult = ImportResult.Applied
            source.transfer = TransferSettings(historyLimit = 20)
            model.import(file)
            assertEquals("Imported.", model.state.value.message)
            assertEquals("20", model.state.value.saved.historyLimit)
            assertEquals(listOf("""{"version": 1}""", """{"version": 1}"""), source.imported)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `a stopped engine changes nothing and says so`() {
        val model = model()
        source.refuseAll = true
        model.edit { it.copy(concurrency = "2") }
        model.save()
        assertEquals("The download engine has stopped, so nothing was changed.", model.state.value.message)
        assertFalse(model.state.value.busy)
    }

    @Test
    fun `saving one field doesn't undo a pause made meanwhile`() {
        val model = model()
        source.transfer = source.transfer.copy(globalPause = true)
        model.edit { it.copy(retries = "2") }
        model.save()
        assertEquals(2, source.transfer.maxRetries)
        assertTrue(source.transfer.globalPause)
    }

    @Test
    fun `a saved folder that is gone is flagged but doesn't block other saves`() {
        source.transfer = TransferSettings(defaultSaveFolder = "/gone")
        val model = model()
        assertTrue(model.state.value.folderMissing)
        model.edit { it.copy(retries = "2") }
        assertTrue(model.state.value.canSave)
        model.save()
        assertEquals(2, source.transfer.maxRetries)
    }

    @Test
    fun `a new folder that isn't there is refused at save`() {
        val model = model()
        model.edit { it.copy(folder = "/nowhere") }
        model.save()
        assertEquals(setOf(SettingField.FOLDER), model.state.value.errors.keys)
        assertNull(source.transfer.defaultSaveFolder)
    }

    @Test
    fun `reset keeps quit after the queue`() {
        val model = model()
        model.edit { it.copy(afterQueue = AfterQueueChoice.QUIT) }
        model.save()
        model.askReset()
        model.confirm()
        assertTrue(model.quitAfterQueue.value)
        assertEquals(AfterQueueChoice.QUIT, model.state.value.saved.afterQueue)
    }

    @Test
    fun `a long history shows the newest few hundred`() {
        source.historyList =
            (1L..300L).map { HistoryEntry(DownloadId(it), "f$it", "/dl/f$it", 1, Instant.fromEpochMilliseconds(it), "https://h/$it") }
        val model = model()
        model.select(Section.HISTORY)
        assertEquals(HISTORY_SHOWN, model.state.value.history.size)
    }

    @Test
    fun `what is typed while a save runs is kept`() {
        val model = model()
        model.edit { it.copy(retries = "2") }
        source.gate = kotlinx.coroutines.CompletableDeferred()
        model.save()
        model.edit { it.copy(historyLimit = "50") }
        source.gate!!.complete(Unit)
        assertEquals("2", model.state.value.saved.retries)
        assertEquals("50", model.state.value.draft.historyLimit)
        assertTrue(model.state.value.dirty)
    }

    @Test
    fun `a file too big to be a settings file isn't read`() {
        val file = Files.createTempFile("anydm-big", ".json")
        try {
            Files.write(file, ByteArray(5 * 1024 * 1024))
            val model = model()
            model.import(file)
            assertTrue(
                model.state.value.message!!
                    .startsWith("Not imported:"),
            )
            assertEquals(emptyList(), source.imported)
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
