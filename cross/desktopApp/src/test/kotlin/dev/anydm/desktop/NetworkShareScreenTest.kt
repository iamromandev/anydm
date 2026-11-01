package dev.anydm.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.core.DownloadStatus.SEEDING
import dev.anydm.engine.net.BondingState
import dev.anydm.engine.torrent.TorrentSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Rule
import kotlin.test.AfterTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class NetworkShareScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val source = FakeSource()

    @AfterTest
    fun close() = scope.cancel()

    private fun show(vararg snapshots: dev.anydm.engine.DownloadSnapshot) {
        source.networks.value = BondingState(listOf(network("wifi").let { it.copy(info = it.info.copy(name = "Wi-Fi")) }, network("usb")))
        source.downloads.value = snapshots.toList()
        val model =
            AppModel(
                source,
                object : FirstRun {
                    override fun isDone() = true

                    override fun markDone() = Unit
                },
                scope,
                FakeFiles(),
            )
        model.start()
        rule.setContent { AnydmTheme(dark = false) { Shell(model) } }
    }

    private fun heightOf(tag: String) = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot().let { it.bottom - it.top }

    @Test
    fun `a card fed by two networks shows its share`() {
        show(snapshot(1, DOWNLOADING, name = "a.iso", networkShare = mapOf("wifi" to 70L, "usb" to 30L)))
        rule.onNodeWithTag("row-share-1").assertTextEquals("Wi-Fi 70% · Network usb 30%")
    }

    @Test
    fun `a card fed by one network or none shows no share`() {
        show(
            snapshot(1, DOWNLOADING, name = "a.iso", networkShare = mapOf("wifi" to 70L)),
            snapshot(2, DOWNLOADING, name = "b.iso"),
        )
        rule.onNodeWithTag("row-share-1").assertDoesNotExist()
        rule.onNodeWithTag("row-share-2").assertDoesNotExist()
    }

    @Test
    fun `the share does not change the card height`() {
        show(
            snapshot(1, DOWNLOADING, name = "a.iso", networkShare = mapOf("wifi" to 70L, "usb" to 30L)),
            snapshot(2, DOWNLOADING, name = "b.iso"),
        )
        assert(heightOf("row-1") == heightOf("row-2")) { "${heightOf("row-1")} vs ${heightOf("row-2")}" }
    }

    @Test
    fun `the networks tab lists name, bytes and percent`() {
        show(snapshot(1, DOWNLOADING, name = "a.iso", networkShare = mapOf("wifi" to 3L * 1024 * 1024, "usb" to 1024L * 1024)))
        rule.onNodeWithTag("row-1").performClick()
        rule.onNodeWithTag("tab-networks").performClick()
        rule.onNodeWithTag("detail-networks").assertIsDisplayed()
        rule.onNodeWithTag("detail-networks-wifi").assertTextEquals("Wi-Fi", "3.0 MB", "75%")
        rule.onNodeWithTag("detail-networks-usb").assertTextEquals("Network usb", "1.0 MB", "25%")
    }

    @Test
    fun `the networks tab says so when nothing was recorded`() {
        show(snapshot(1, DOWNLOADING, name = "a.iso"))
        rule.onNodeWithTag("row-1").performClick()
        rule.onNodeWithTag("tab-networks").performClick()
        rule.onNodeWithTag("detail-networks-empty").assertIsDisplayed()
    }

    private fun rename(
        key: String,
        name: String,
    ) {
        source.networks.value =
            BondingState(
                source.networks.value.networks
                    .map { if (it.info.key == key) it.copy(info = it.info.copy(name = name)) else it },
            )
        rule.waitForIdle()
    }

    @Test
    fun `the card and the tab follow a changing share`() {
        show(snapshot(1, DOWNLOADING, name = "a.iso", networkShare = mapOf("wifi" to 70L, "usb" to 30L)))
        rule.onNodeWithTag("row-1").performClick()
        rule.onNodeWithTag("tab-networks").performClick()
        source.downloads.value = listOf(snapshot(1, DOWNLOADING, name = "a.iso", networkShare = mapOf("wifi" to 50L, "usb" to 50L)))
        rule.waitForIdle()
        rule.onNodeWithTag("row-share-1").assertTextEquals("Network usb 50% · Wi-Fi 50%")
        rule.onNodeWithTag("detail-networks-wifi").assertTextEquals("Wi-Fi", "50 B", "50%")
        rule.onNodeWithTag("detail-networks-usb").assertTextEquals("Network usb", "50 B", "50%")
    }

    @Test
    fun `the card and the tab follow a renamed network`() {
        show(snapshot(1, DOWNLOADING, name = "a.iso", networkShare = mapOf("wifi" to 70L, "usb" to 30L)))
        rule.onNodeWithTag("row-1").performClick()
        rule.onNodeWithTag("tab-networks").performClick()
        rename("wifi", "Office")
        rule.onNodeWithTag("row-share-1").assertTextEquals("Office 70% · Network usb 30%")
        rule.onNodeWithTag("detail-networks-wifi").assertTextEquals("Office", "70 B", "70%")
    }

    @Test
    fun `a network that leaves keeps its last name`() {
        show(snapshot(1, DOWNLOADING, name = "a.iso", networkShare = mapOf("wifi" to 70L, "usb" to 30L)))
        source.networks.value =
            BondingState(
                source.networks.value.networks
                    .filter { it.info.key != "usb" },
            )
        rule.waitForIdle()
        rule.onNodeWithTag("row-share-1").assertTextEquals("Wi-Fi 70% · Network usb 30%")
    }

    @Test
    fun `the networks tab of a torrent lists its share`() {
        val summary =
            TorrentSummary(
                peers = 1,
                seeds = 1,
                availability = 1.0,
                downloadRate = 0,
                uploadRate = 0,
                uploaded = 0,
                ratio = 0.5,
                finishedSeconds = 0,
            )
        show(snapshot(1, SEEDING, name = "t", torrent = summary, networkShare = mapOf("wifi" to 60L, "usb" to 40L)))
        rule.onNodeWithTag("row-1").performClick()
        rule.onNodeWithTag("tab-networks").performClick()
        rule.onNodeWithTag("detail-networks-wifi").assertTextEquals("Wi-Fi", "60 B", "60%")
    }

    @Test
    fun `a single network reads 100 percent in the tab`() {
        show(snapshot(1, DOWNLOADING, name = "a.iso", networkShare = mapOf("wifi" to 500L)))
        rule.onNodeWithTag("row-1").performClick()
        rule.onNodeWithTag("tab-networks").performClick()
        rule.onNodeWithTag("detail-networks-wifi").assertTextEquals("Wi-Fi", "500 B", "100%")
    }

    @Test
    fun `selecting another job leaves the networks tab`() {
        show(
            snapshot(1, DOWNLOADING, name = "a.iso", networkShare = mapOf("wifi" to 500L)),
            snapshot(2, DOWNLOADING, name = "b.iso"),
        )
        rule.onNodeWithTag("row-1").performClick()
        rule.onNodeWithTag("tab-networks").performClick()
        rule.onNodeWithTag("detail-networks").assertIsDisplayed()
        rule.onNodeWithTag("row-2").performClick()
        rule.onNodeWithTag("detail-files").assertIsDisplayed()
        rule.onNodeWithTag("detail-networks").assertDoesNotExist()
    }
}
