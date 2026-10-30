package dev.anydm.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import dev.anydm.engine.core.CategoryId
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.ProfileId
import dev.anydm.engine.files.Category
import dev.anydm.engine.files.ClashPolicy
import dev.anydm.engine.files.Profile
import dev.anydm.engine.store.HistoryEntry
import dev.anydm.engine.store.TransferSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Rule
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class SettingsScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val source = FakeSource()
    private val folder: Path = Files.createTempDirectory("anydm-settings")
    private lateinit var model: AppModel

    private val pickers =
        object : Pickers {
            var save: Path? = null

            override fun torrentFile(): Path? = null

            override fun folder(): Path? = null

            override fun keyFile(): Path? = null

            override fun saveSettingsFile(name: String) = save
        }

    @AfterTest
    fun close() {
        scope.cancel()
        folder.toFile().deleteRecursively()
    }

    private fun show() {
        model =
            AppModel(
                source,
                object : FirstRun {
                    override fun isDone() = true

                    override fun markDone() = Unit
                },
                scope,
                FakeFiles(),
                io = Dispatchers.Unconfined,
            )
        model.start()
        model.navigate(Route.Settings)
        rule.setContent { AnydmTheme(dark = false) { Shell(model, pickers) } }
        rule.waitForIdle()
    }

    private fun tag(name: String): SemanticsNodeInteraction = rule.onNodeWithTag(name, useUnmergedTree = true)

    // The section's content scrolls; the section list and the save bar don't.
    private fun shown(name: String): SemanticsNodeInteraction =
        rule.onNodeWithTag(name).also { node -> runCatching { node.performScrollTo() } }

    private fun click(name: String) = shown(name).performClick()

    private fun type(
        name: String,
        text: String,
    ) = shown(name).apply {
        performTextClearance()
        performTextInput(text)
    }

    @Test
    fun `each transfer section shows the saved values with save off`() {
        source.transfer = TransferSettings(clashPolicy = ClashPolicy.SKIP, connectionsPerDownload = 8, concurrencyLimit = 2)
        show()
        rule.onNodeWithTag("set-clash-skip").assertIsSelected()
        rule.onNodeWithTag("settings-save").assertIsNotEnabled()
        click("settings-section-speed")
        rule.onNodeWithTag("set-connections").assertTextContains("8")
        click("settings-section-queue")
        rule.onNodeWithTag("set-concurrency").assertTextContains("2")
    }

    @Test
    fun `an edit saves through the engine`() {
        show()
        type("set-folder", folder.toString())
        click("set-clash-overwrite")
        click("settings-save")
        rule.waitForIdle()
        assertEquals(folder.toString(), source.transfer.defaultSaveFolder)
        assertEquals(ClashPolicy.OVERWRITE, source.transfer.clashPolicy)
        tag("settings-message").assertTextContains("Saved.")
        rule.onNodeWithTag("settings-save").assertIsNotEnabled()
    }

    @Test
    fun `a value out of range says why and keeps save off`() {
        show()
        click("settings-section-speed")
        type("set-connections", "17")
        tag("set-connections-error").assertIsDisplayed()
        rule.onNodeWithTag("settings-save").assertIsNotEnabled()
        type("set-connections", "16")
        rule.onNodeWithTag("settings-save").assertIsEnabled()
    }

    @Test
    fun `leaving with unsaved changes asks first`() {
        show()
        click("settings-section-queue")
        type("set-concurrency", "1")
        click("settings-section-categories")
        rule.onNodeWithTag("settings-confirm").assertIsDisplayed()
        rule.onNodeWithTag("settings-confirm-cancel").performClick()
        rule.onNodeWithTag("set-concurrency").assertTextContains("1")
    }

    @Test
    fun `a category with a bad template can't be saved, a good one is added`() {
        show()
        click("settings-section-categories")
        click("category-new")
        type("category-name", "Videos")
        type("category-folder", folder.toString())
        type("category-template", "{nope}")
        tag("category-template-error").assertTextContains("{title}", substring = true)
        rule.onNodeWithTag("category-save").assertIsNotEnabled()
        type("category-template", "{title} [{id}]")
        type("category-extensions", "mkv, mp4")
        click("category-save")
        rule.waitForIdle()
        assertEquals("Videos", source.categoryList.single().name)
        rule.onNodeWithTag("category-Videos").assertIsDisplayed()
        // The sidebar has it too.
        assertEquals(
            listOf("Videos"),
            model.state.value.categories
                .map { it.name },
        )
    }

    @Test
    fun `deleting the default profile leaves none`() {
        val clips = Profile(ProfileId(5), "Clips", null, null, null, null, null)
        source.profileList = listOf(clips)
        source.defaultProfileId = clips.id
        show()
        click("settings-section-profiles")
        rule.onNodeWithTag("profile-default-0").assertIsSelected()
        click("profile-delete-0")
        rule.onNodeWithTag("settings-confirm-ok").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("profile-default-none").assertIsSelected()
        assertEquals(emptyList(), source.profileList)
    }

    @Test
    fun `history shows and clears after a confirmation`() {
        source.historyList =
            listOf(HistoryEntry(DownloadId(1), "a.zip", "/dl/a.zip", 10, Instant.parse("2026-10-29T10:00:00Z"), "https://h/a"))
        source.categoryList = listOf(Category(CategoryId(1), "Music", "/m", null, emptyList(), 0))
        show()
        click("settings-section-history")
        rule.onNodeWithTag("history-0").assertIsDisplayed()
        click("history-clear")
        rule.onNodeWithTag("settings-confirm-ok").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("history-empty").assertIsDisplayed()
    }

    @Test
    fun `export writes the chosen file and reset asks first`() {
        source.exported = """{"version": 1}"""
        pickers.save = folder.resolve("out.json")
        show()
        click("settings-section-backup")
        click("backup-export")
        rule.waitUntil(5_000) { Files.exists(folder.resolve("out.json")) }
        assertEquals("""{"version": 1}""", Files.readString(folder.resolve("out.json")))

        source.transfer = TransferSettings(clashPolicy = ClashPolicy.SKIP)
        click("backup-reset")
        rule.onNodeWithTag("settings-confirm-ok").performClick()
        rule.waitForIdle()
        assertEquals(ClashPolicy.RENAME, source.transfer.clashPolicy)
    }
}
