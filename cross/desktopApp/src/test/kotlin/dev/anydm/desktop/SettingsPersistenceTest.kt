package dev.anydm.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** HomeScreen against fake stores: what it loads, when it saves, and on which thread. Nothing here touches a real folder or keychain. */
@OptIn(ExperimentalTestApi::class)
class SettingsPersistenceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val h = PersistHarness()
    private val mb = 1024L * 1024

    private fun savedFile(): SettingsFile {
        val nets =
            populatedSample().networks.map {
                if (it.id == "wifi") {
                    it.copy(name = "Home", color = NetworkColor.Pink, proxy = Proxy(ProxyType.Socks5, "127.0.0.1", 1080, "alice"))
                } else {
                    it
                }
            }
        val limits = initialLimits(nets, sampleUsage).copy(total = 20 * mb, concurrency = 3)
        return toFile(ThemeChoice.Dark, SpeedUnit.Mbps, "/home/test/Movies", limits, nets)
    }

    private fun stores(
        settings: SettingsStore,
        secrets: SecretStore = InMemorySecretStore(),
    ) = AppStores(settings, secrets, 500)

    // AC6: restore

    @Test
    fun aSavedFileIsRestoredIntoTheApp() =
        runComposeUiTest {
            val store = InMemorySettingsStore(savedFile())
            with(h) {
                home(stores(store), dark = false)
                await("the dark theme") { theme() == "dark" }
                onNodeWithTag("unit-mbps").assert(hasContentDescription("Mbps selected"))
                modComma()
                onNodeWithTag("settings-folder").assertTextEquals("~/Movies")
                nav("speeddata")
                onNodeWithTag("total-field").assertTextEquals("160")
                nav("networks")
                onNodeWithTag("network-page-name").assertTextEquals("Home")
                onNodeWithTag("swatch-pink").assertIsSelected()
                onNodeWithTag("network-page-proxy-summary", useUnmergedTree = true).assertTextEquals("SOCKS5 alice@127.0.0.1:1080")
            }
        }

    @Test
    fun aSecondSessionOnTheSameStoreRestoresWhatTheFirstChangedButNotSelection() {
        val store = InMemorySettingsStore()
        runComposeUiTest {
            with(h) {
                home(stores(store), dark = false)
                await("the first load") { true }
                onNodeWithTag("select-1").performClick()
                modComma()
                nav("appearance")
                onNodeWithTag("theme-dark").performClick()
                onNodeWithTag("settings-unit-mbps").performClick()
                nav("networks")
                onNodeWithTag("network-page-rename").performClick()
                onNodeWithTag("network-rename-field").performTextClearance()
                onNodeWithTag("network-rename-field").performTextInput("Office")
                onNodeWithTag("network-rename-field").performKeyInput { pressKey(Key.Enter) }
                mainClock.advanceTimeBy(700)
                await("the save") { store.saves.isNotEmpty() }
            }
        }
        runComposeUiTest {
            with(h) {
                home(stores(store), dark = false)
                await("the dark theme") { theme() == "dark" }
                onNodeWithTag("unit-mbps").assert(hasContentDescription("Mbps selected"))
                onNodeWithTag("network-chip").performClick()
                onNodeWithTag("network-name-wifi").assertTextEquals("Office")
                onNodeWithTag("select-1").assertIsOff()
                onNodeWithTag("row-1").assertIsDisplayed()
                onNodeWithTag("row-2").assertIsDisplayed()
            }
        }
    }

    // Writing

    @Test
    fun restoringAFileDoesNotWriteItBack() =
        runComposeUiTest {
            val store = FakeSettingsStore(LoadResult.Loaded(savedFile()))
            mainClock.autoAdvance = false
            with(h) {
                home(stores(store), dark = false)
                await("the dark theme") { theme() == "dark" }
                mainClock.advanceTimeBy(2_000)
                Thread.sleep(100)
                assertTrue(store.saves.isEmpty(), "restoring must not rewrite the file")
            }
        }

    @Test
    fun threeQuickChangesMakeOneSaveAfterTheDebounce() =
        runComposeUiTest {
            val store = FakeSettingsStore()
            mainClock.autoAdvance = false
            with(h) {
                home(stores(store))
                await("the load") { store.loads.isNotEmpty() }
                mainClock.advanceTimeByFrame()
                repeat(3) {
                    onNodeWithTag("theme-toggle").performClick()
                    mainClock.advanceTimeBy(100)
                }
                mainClock.advanceTimeBy(150)
                Thread.sleep(100)
                assertTrue(store.saves.isEmpty(), "nothing is written before the debounce is over")
                mainClock.advanceTimeBy(400)
                await("the save") { store.saves.isNotEmpty() }
                mainClock.advanceTimeBy(2_000)
                Thread.sleep(100)
                assertEquals(1, store.saves.size)
                assertEquals(ThemeChoice.Light, store.saves.single().theme)
            }
        }

    @Test
    fun aPendingChangeIsWrittenByFlushWithoutWaitingForTheDebounce() =
        runComposeUiTest {
            val store = FakeSettingsStore()
            val s = stores(store)
            mainClock.autoAdvance = false
            with(h) {
                home(s)
                await("the load") { store.loads.isNotEmpty() }
                mainClock.advanceTimeByFrame()
                onNodeWithTag("theme-toggle").performClick()
                mainClock.advanceTimeBy(100)
                assertTrue(store.saves.isEmpty())
                s.flush()
                assertEquals(1, store.saves.size)
                assertEquals(ThemeChoice.Light, store.saves.single().theme)
                mainClock.advanceTimeBy(2_000)
                Thread.sleep(100)
                assertEquals(1, store.saves.size, "the debounced write must not repeat it")
            }
        }

    @Test
    fun aChangeBeforeTheLoadHasFinishedIsNeverWrittenOverTheFile() =
        runComposeUiTest {
            val store = FakeSettingsStore()
            val s = stores(store)
            with(h) {
                home(s)
                s.flush()
                assertTrue(store.saves.isEmpty())
            }
        }

    @Test
    fun savesAreMadeFromTheWholeAppState() =
        runComposeUiTest {
            val store = FakeSettingsStore()
            mainClock.autoAdvance = false
            with(h) {
                home(stores(store))
                await("the load") { store.loads.isNotEmpty() }
                mainClock.advanceTimeByFrame()
                picker.folder = "/home/test/Movies"
                mainClock.autoAdvance = true
                modComma()
                onNodeWithTag("settings-folder-change").performClick()
                nav("appearance")
                onNodeWithTag("theme-dark").performClick()
                onNodeWithTag("settings-unit-mbps").performClick()
                mainClock.autoAdvance = false
                mainClock.advanceTimeBy(700)
                await("the save") { store.saves.isNotEmpty() }
                val f = store.saves.last()
                assertEquals(ThemeChoice.Dark, f.theme)
                assertEquals(SpeedUnit.Mbps, f.unit)
                assertEquals("/home/test/Movies", f.saveFolder)
                assertEquals(listOf("wifi", "usb"), f.networks.map { it.id })
            }
        }

    // Notices

    @Test
    fun aResetShowsTheNoticeOnceAndItGoesAway() =
        runComposeUiTest {
            val store = FakeSettingsStore(LoadResult.Reset("not valid settings"))
            mainClock.autoAdvance = false
            with(h) {
                home(stores(store))
                await("the notice") { onNodeWithTag("notice").isShown() }
                onNodeWithTag("notice").assertTextEquals("Settings couldn't be read and were reset")
                mainClock.advanceTimeBy(6_500)
                onNodeWithTag("notice").assertDoesNotExist()
                mainClock.autoAdvance = true
                onNodeWithTag("theme-toggle").performClick()
                mainClock.autoAdvance = false
                mainClock.advanceTimeBy(1_000)
                onNodeWithTag("notice").assertDoesNotExist()
            }
        }

    @Test
    fun aMissingFileShowsNoNotice() =
        runComposeUiTest {
            val store = FakeSettingsStore()
            with(h) {
                home(stores(store))
                await("the load") { store.loads.isNotEmpty() }
                waitForIdle()
                onNodeWithTag("notice").assertDoesNotExist()
            }
        }

    @Test
    fun aSaveThatFailsShowsANoticeOnceAndTheAppKeepsRunning() =
        runComposeUiTest {
            val store = FakeSettingsStore(saveResult = SaveResult.Failed("couldn't write the settings file"))
            mainClock.autoAdvance = false
            with(h) {
                home(stores(store))
                await("the load") { store.loads.isNotEmpty() }
                mainClock.advanceTimeByFrame()
                onNodeWithTag("theme-toggle").performClick()
                mainClock.advanceTimeBy(700)
                await("the notice") { onNodeWithTag("notice").isShown() }
                onNodeWithTag("notice").assertTextEquals("Settings couldn't be saved; changes last until you quit")
                mainClock.advanceTimeBy(6_500)
                onNodeWithTag("notice").assertDoesNotExist()
                onNodeWithTag("theme-toggle").performClick()
                mainClock.advanceTimeBy(700)
                await("the second attempt") { store.saves.size >= 2 }
                mainClock.advanceTimeBy(100)
                onNodeWithTag("notice").assertDoesNotExist()
                themeIs("dark")
            }
        }

    // NFR1, NFR3: threads

    @Test
    fun theStoreIsNeverCalledOnTheUiThread() =
        runComposeUiTest {
            val store = FakeSettingsStore(LoadResult.Loaded(savedFile()))
            mainClock.autoAdvance = false
            with(h) {
                home(stores(store), dark = false)
                store.forbidden.add(uiThread ?: error("no ui thread"))
                await("the dark theme") { theme() == "dark" }
                mainClock.autoAdvance = true
                onNodeWithTag("theme-toggle").performClick()
                mainClock.autoAdvance = false
                mainClock.advanceTimeBy(700)
                await("the save") { store.saves.isNotEmpty() }
                assertEquals(0, store.onForbiddenThread)
                assertTrue(store.loads.all { it.startsWith("DefaultDispatcher-worker") }, store.loads.toString())
                assertTrue(store.saveThreads.all { it.startsWith("DefaultDispatcher-worker") }, store.saveThreads.toString())
            }
        }

    // NFR1: the bytes on disk

    @Test
    fun aProxyPasswordIsNeverInTheFileBytes() =
        runComposeUiTest {
            val secret = "never-on-disk-pass-123"
            val dir = tmp.root.toPath().resolve("anydm")
            val secrets = FakeSecretStore()
            mainClock.autoAdvance = false
            with(h) {
                home(stores(FileSettingsStore(dir), secrets))
                mainClock.advanceTimeByFrame()
                mainClock.autoAdvance = true
                saveProxy(password = secret)
                mainClock.autoAdvance = false
                mainClock.advanceTimeBy(700)
                await("the file") { Files.exists(dir.resolve("settings.json")) }
                val bytes = dir.resolve("settings.json").readBytes()
                val text = bytes.decodeToString()
                assertFalse(text.contains(secret), "the password is in settings.json")
                assertTrue(text.contains("\"host\": \"127.0.0.1\""), text)
                assertTrue(text.contains("\"user\": \"alice\""), text)
                assertEquals(secret, secrets.values["proxy:wifi"])
                assertEquals(listOf("settings.json"), dir.toFile().list()!!.toList())
            }
        }

    @Test
    fun aRealFileStoreRoundTripsASessionThroughTheDisk() {
        val dir = tmp.root.toPath().resolve("anydm")
        runComposeUiTest {
            with(h) {
                home(stores(FileSettingsStore(dir)), dark = false)
                onNodeWithTag("theme-toggle").performClick()
                themeIs("dark")
                mainClock.advanceTimeBy(700)
                await("the file") { Files.exists(dir.resolve("settings.json")) }
            }
        }
        runComposeUiTest {
            with(h) {
                home(stores(FileSettingsStore(dir)), dark = false)
                await("the restored theme") { theme() == "dark" }
            }
        }
        assertNotEquals(0, Files.size(dir.resolve("settings.json")))
        assertIs<LoadResult.Loaded>(FileSettingsStore(dir).load())
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.isShown() = runCatching { assertIsDisplayed() }.isSuccess

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertIsOff() =
        assert(
            androidx.compose.ui.test.SemanticsMatcher.expectValue(
                SemanticsProperties.ToggleableState,
                androidx.compose.ui.state.ToggleableState.Off,
            ),
        )
}
