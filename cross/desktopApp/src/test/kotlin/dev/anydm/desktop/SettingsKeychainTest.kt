package dev.anydm.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** HomeScreen against a fake keychain: what is written, when, and what the user is told when it fails. */
@OptIn(ExperimentalTestApi::class)
class SettingsKeychainTest {
    private val h = PersistHarness()
    private val secret = "k3ychain-secret-pw"

    private fun stores(
        secrets: SecretStore,
        settings: SettingsStore = FakeSettingsStore(),
    ) = AppStores(settings, secrets, 500)

    private fun withProxyOnWifi(): SettingsFile {
        val nets =
            populatedSample().networks.map {
                if (it.id == "wifi") it.copy(proxy = Proxy(ProxyType.Socks5, "127.0.0.1", 1080, "alice")) else it
            }
        return toFile(ThemeChoice.System, SpeedUnit.MBps, null, initialLimits(nets, sampleUsage), nets)
    }

    private fun noProxyUserFile(): SettingsFile {
        val nets =
            populatedSample().networks.map {
                if (it.id == "wifi") it.copy(proxy = Proxy(ProxyType.Http, "h.example", 80)) else it
            }
        return toFile(ThemeChoice.System, SpeedUnit.MBps, null, initialLimits(nets, sampleUsage), nets)
    }

    // AC12

    @Test
    fun savingAProxyWithAPasswordSetsTheKeychainEntry() =
        runComposeUiTest {
            val fake = FakeSecretStore()
            with(h) {
                home(stores(fake))
                saveProxy(password = secret)
                await("the keychain call") { fake.calls.contains("set proxy:wifi") }
                assertEquals(secret, fake.values["proxy:wifi"])
                assertEquals(listOf("set proxy:wifi"), fake.calls.toList())
            }
        }

    @Test
    fun savingAProxyWithoutAPasswordDeletesTheEntry() =
        runComposeUiTest {
            val fake = FakeSecretStore(mapOf("proxy:wifi" to "old"))
            with(h) {
                home(stores(fake))
                saveProxy(password = "")
                await("the keychain call") { fake.calls.contains("delete proxy:wifi") }
                assertTrue(fake.values.isEmpty())
            }
        }

    @Test
    fun choosingNoneDeletesTheEntry() =
        runComposeUiTest {
            val fake = FakeSecretStore()
            with(h) {
                home(stores(fake))
                saveProxy(password = secret)
                await("the set") { fake.calls.contains("set proxy:wifi") }
                onNodeWithTag("network-page-proxy-edit").performClick()
                onNodeWithTag("proxy-page-type-none").performClick()
                onNodeWithTag("proxy-page-save").performClick()
                await("the delete") { fake.calls.contains("delete proxy:wifi") }
                assertTrue(fake.values.isEmpty())
            }
        }

    @Test
    fun theNetworksMenuPageSavesToTheKeychainToo() =
        runComposeUiTest {
            val fake = FakeSecretStore()
            with(h) {
                home(stores(fake))
                onNodeWithTag("network-chip").performClick()
                onNodeWithTag("network-chevron-wifi").performClick()
                onNodeWithTag("network-page-proxy-edit").performClick()
                onNodeWithTag("proxy-page-type-http").performClick()
                onNodeWithTag("proxy-page-host").performTextReplacement("h.example")
                onNodeWithTag("proxy-page-port").performTextReplacement("80")
                onNodeWithTag("proxy-page-user").performTextReplacement("bob")
                typePassword(secret)
                onNodeWithTag("proxy-page-save").performClick()
                await("the keychain call") { fake.calls.contains("set proxy:wifi") }
                assertEquals(secret, fake.values["proxy:wifi"])
            }
        }

    // AC13

    @Test
    fun theKeychainPasswordFillsTheEditorMasked() =
        runComposeUiTest {
            val fake = FakeSecretStore(mapOf("proxy:wifi" to secret))
            with(h) {
                home(stores(fake, InMemorySettingsStore(withProxyOnWifi())))
                await("the get") { fake.calls.contains("get proxy:wifi") }
                waitForIdle()
                modComma()
                nav("networks")
                onNodeWithTag("network-page-proxy-edit").performClick()
                onNodeWithContentDescription("Password, hidden").assertIsDisplayed()
                assertFalse(treeHas(secret), "the password must not be in the semantics tree while hidden")
                onNodeWithContentDescription("Show password").performClick()
                onNodeWithContentDescription("Password").assertTextEquals(secret, includeEditableText = true)
            }
        }

    @Test
    fun onlyNetworksWithAProxyUserAreLookedUp() =
        runComposeUiTest {
            val fake = FakeSecretStore()
            with(h) {
                home(stores(fake, InMemorySettingsStore(noProxyUserFile())))
                await("the load") { onNodeWithTag("home-root").isThere() }
                waitForIdle()
                Thread.sleep(100)
                waitForIdle()
                assertEquals(emptyList(), fake.calls.toList())
            }
        }

    @Test
    fun theLookupAtStartRunsOnlyForTheNetworkWithAUser() =
        runComposeUiTest {
            val fake = FakeSecretStore()
            with(h) {
                home(stores(fake, InMemorySettingsStore(withProxyOnWifi())))
                await("the get") { fake.calls.isNotEmpty() }
                assertEquals(listOf("get proxy:wifi"), fake.calls.toList())
            }
        }

    // S3: the password filled at start is older than anything the user did since

    @Test
    fun aPasswordTheUserClearedWhileTheLookupWasSlowIsNotBroughtBack() =
        runComposeUiTest {
            val gate = CountDownLatch(1)
            val fake = FakeSecretStore(mapOf("proxy:wifi" to secret), getGate = gate)
            with(h) {
                home(stores(fake, InMemorySettingsStore(withProxyOnWifi())))
                await("the slow get") { fake.calls.contains("get proxy:wifi") }
                saveProxy(host = "10.0.0.9", password = "")
                gate.countDown()
                await("the delete after the get") { fake.calls.contains("delete proxy:wifi") }
                waitForIdle()
                onNodeWithTag("network-page-proxy-edit").performClick()
                onNodeWithContentDescription("Show password").performClick()
                onNodeWithContentDescription("Password").assertTextEquals("", includeEditableText = true)
                assertFalse(treeHas(secret))
            }
        }

    @Test
    fun aPasswordTheUserTypedWhileTheLookupWasSlowIsKept() =
        runComposeUiTest {
            val gate = CountDownLatch(1)
            val fake = FakeSecretStore(mapOf("proxy:wifi" to secret), getGate = gate)
            with(h) {
                home(stores(fake, InMemorySettingsStore(withProxyOnWifi())))
                await("the slow get") { fake.calls.contains("get proxy:wifi") }
                saveProxy(host = "10.0.0.9", password = "typed-later")
                gate.countDown()
                await("the set after the get") { fake.calls.contains("set proxy:wifi") }
                waitForIdle()
                onNodeWithTag("network-page-proxy-edit").performClick()
                onNodeWithContentDescription("Show password").performClick()
                onNodeWithContentDescription("Password").assertTextEquals("typed-later", includeEditableText = true)
                assertEquals("typed-later", fake.values["proxy:wifi"])
            }
        }

    @Test
    fun aNetworkTheUserDidNotTouchStillGetsItsPasswordFromASlowLookup() =
        runComposeUiTest {
            val gate = CountDownLatch(1)
            val fake = FakeSecretStore(mapOf("proxy:wifi" to secret), getGate = gate)
            with(h) {
                home(stores(fake, InMemorySettingsStore(withProxyOnWifi())))
                await("the slow get") { fake.calls.contains("get proxy:wifi") }
                modComma()
                nav("networks")
                onNodeWithTag("settings-network-usb").performClick()
                onNodeWithTag("settings-network-wifi").performClick()
                gate.countDown()
                waitForIdle()
                Thread.sleep(150)
                waitForIdle()
                onNodeWithTag("network-page-proxy-edit").performClick()
                onNodeWithContentDescription("Show password").performClick()
                onNodeWithContentDescription("Password").assertTextEquals(secret, includeEditableText = true)
            }
        }

    // S2: leaving the app waits for a keychain write that is still running

    @Test
    fun flushWaitsForTheKeychainWriteOfAProxySave() =
        runComposeUiTest {
            val gate = CountDownLatch(1)
            val fake = FakeSecretStore(setGate = gate)
            val s = AppStores(FakeSettingsStore(), fake, 3_000)
            with(h) {
                home(s)
                saveProxy(password = secret)
                await("the set to start") { fake.calls.contains("set proxy:wifi") }
                thread {
                    Thread.sleep(250)
                    gate.countDown()
                }
                val start = System.nanoTime()
                s.flush()
                val ms = (System.nanoTime() - start) / 1_000_000
                assertEquals(secret, fake.values["proxy:wifi"], "the exit must not leave before the keychain has the password")
                assertTrue(ms in 150..2_500, "took $ms ms")
            }
        }

    // AC14

    @Test
    fun aFailingKeychainStillSavesTheProxyAndShowsTheNote() =
        runComposeUiTest {
            val fake = FakeSecretStore(failWith = "keychain error")
            val settings = FakeSettingsStore()
            with(h) {
                home(stores(fake, settings))
                saveProxy(password = secret)
                await("the note") { onNodeWithTag("network-page-proxy-note").isThere() }
                onNodeWithTag("network-page-proxy-note").assertTextEquals("Password not saved: keychain error")
                onNodeWithTag("network-page-proxy-summary", useUnmergedTree = true).assertTextEquals("SOCKS5 alice@127.0.0.1:1080")
            }
        }

    @Test
    fun withNoKeychainTheNoteSaysSo() =
        runComposeUiTest {
            with(h) {
                home(stores(UnavailableSecretStore))
                saveProxy(password = secret)
                await("the note") { onNodeWithTag("network-page-proxy-note").isThere() }
                onNodeWithTag("network-page-proxy-note").assertTextEquals("Password not saved: no system keychain")
            }
        }

    @Test
    fun theNoteAlsoShowsInTheEditorAndThePasswordWorksForTheSession() =
        runComposeUiTest {
            val fake = FakeSecretStore(failWith = "keychain error")
            with(h) {
                home(stores(fake))
                saveProxy(password = secret)
                await("the note") { onNodeWithTag("network-page-proxy-note").isThere() }
                onNodeWithTag("network-page-proxy-edit").performClick()
                onNodeWithTag("proxy-page-note").assertTextEquals("Password not saved: keychain error")
                onNodeWithContentDescription("Show password").performClick()
                onNodeWithContentDescription("Password").assertTextEquals(secret, includeEditableText = true)
            }
        }

    @Test
    fun aFailedKeychainNeverPutsThePasswordAnywhereElse() =
        runComposeUiTest {
            val fake = FakeSecretStore(failWith = "keychain error")
            val settings = FakeSettingsStore()
            mainClock.autoAdvance = false
            with(h) {
                home(stores(fake, settings))
                await("the load") { settings.loads.isNotEmpty() }
                mainClock.advanceTimeByFrame()
                mainClock.autoAdvance = true
                saveProxy(password = secret)
                mainClock.autoAdvance = false
                mainClock.advanceTimeBy(700)
                await("the save") { settings.saves.isNotEmpty() }
                val text = settingsJson.encodeToString(SettingsFile.serializer(), settings.saves.last())
                assertFalse(text.contains(secret))
                assertTrue(text.contains("alice"))
                assertTrue(fake.values.isEmpty())
            }
        }

    @Test
    fun aLaterSuccessfulSaveClearsTheNote() =
        runComposeUiTest {
            val fake = FakeSecretStore(failWith = "keychain error")
            with(h) {
                home(stores(fake))
                saveProxy(password = secret)
                await("the note") { onNodeWithTag("network-page-proxy-note").isThere() }
                fake.failWith = null
                onNodeWithTag("network-page-proxy-edit").performClick()
                onNodeWithTag("proxy-page-host").performTextReplacement("10.0.0.9")
                onNodeWithTag("proxy-page-save").performClick()
                await("the note to go") { !onNodeWithTag("network-page-proxy-note").isThere() }
                assertEquals(secret, fake.values["proxy:wifi"])
            }
        }

    @Test
    fun aFailingLookupAtStartSaysThePasswordCouldNotBeRead() =
        runComposeUiTest {
            val fake = FakeSecretStore(failWith = "no system keychain")
            with(h) {
                home(stores(fake, InMemorySettingsStore(withProxyOnWifi())))
                await("the get") { fake.calls.isNotEmpty() }
                waitForIdle()
                modComma()
                nav("networks")
                await("the note") { onNodeWithTag("network-page-proxy-note").isThere() }
                onNodeWithTag("network-page-proxy-note").assertTextEquals("Couldn't read the saved password: no system keychain")
            }
        }

    @Test
    fun aFailingDeleteWithNoPasswordShowsNoNote() =
        runComposeUiTest {
            val fake = FakeSecretStore(failWith = "keychain error")
            with(h) {
                home(stores(fake))
                saveProxy(password = "")
                await("the delete") { fake.calls.contains("delete proxy:wifi") }
                waitForIdle()
                assertFalse(onNodeWithTag("network-page-proxy-note").isThere())
            }
        }

    // AC15

    @Test
    fun noKeychainCallRunsOnTheUiThread() =
        runComposeUiTest {
            val fake = FakeSecretStore(mapOf("proxy:wifi" to secret))
            with(h) {
                home(stores(fake, InMemorySettingsStore(withProxyOnWifi())))
                fake.forbidden.add(uiThread ?: error("no ui thread"))
                await("the get") { fake.calls.isNotEmpty() }
                saveProxy(host = "10.0.0.1", password = "another")
                await("the set") { fake.calls.contains("set proxy:wifi") }
                assertEquals(0, fake.onForbiddenThread)
                assertTrue(fake.threads.all { it.startsWith("DefaultDispatcher-worker") }, fake.threads.toString())
            }
        }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.isThere() = runCatching { assertExists() }.isSuccess
}
