package dev.anydm.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.AnnotatedString
import dev.anydm.engine.torrent.TorrentResult
import java.nio.file.Path
import kotlin.test.assertEquals

/** A picker that never opens an OS dialog. */
class PersistPicker(
    var folder: String? = null,
) : FilePicker {
    override fun pickTorrent(): Path? = null

    override fun pickFolder(current: String): String? = folder
}

/**
 * Shared set-up for the persistence tests. It only ever builds fakes: the files are fake, the home is "/home/test",
 * and the stores are whatever the test passes in, never the real app-data folder or keychain.
 */
@OptIn(ExperimentalTestApi::class)
class PersistHarness {
    val picker = PersistPicker()
    val env =
        FilesEnv(
            picker,
            object : TorrentLoader {
                override suspend fun load(path: Path): TorrentResult = TorrentResult.Err("Can't read file")
            },
            "/home/test",
        )
    private var tick = 0L

    /** The thread the composition runs on: no store call may come from it. */
    @Volatile
    var uiThread: Thread? = null

    fun ComposeUiTest.home(
        stores: AppStores,
        dark: Boolean = true,
    ) = setContent {
        uiThread = Thread.currentThread()
        HomeScreen(
            populatedSample(),
            dark,
            clipboardText = { null },
            files = env,
            bus =
                CommandBus {
                    tick += 1_000
                    tick
                },
            platform = Platform.Mac,
            stores = stores,
        )
    }

    /** Frames and real time pass until [what] holds: the stores answer on other threads. */
    fun ComposeUiTest.await(
        what: String,
        timeoutMs: Long = 5_000,
        cond: () -> Boolean,
    ) {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (!cond()) {
            check(System.nanoTime() < end) { "timed out waiting for $what" }
            mainClock.advanceTimeByFrame()
            Thread.sleep(5)
        }
    }

    fun ComposeUiTest.theme() = onNodeWithTag("home-root").fetchSemanticsNode().config[SemanticsProperties.StateDescription]

    fun ComposeUiTest.themeIs(state: String) = assertEquals(state, theme())

    fun ComposeUiTest.modComma() =
        onNodeWithTag("home-root").performKeyInput {
            keyDown(Key.MetaLeft)
            pressKey(Key.Comma)
            keyUp(Key.MetaLeft)
        }

    fun ComposeUiTest.nav(section: String) = onNodeWithTag("settings-nav-$section").performClick()

    /** Settings, Networks, the proxy editor of the selected (first) network, filled in and saved. */
    fun ComposeUiTest.saveProxy(
        host: String = "127.0.0.1",
        port: String = "1080",
        user: String = "alice",
        password: String = "",
    ) {
        modComma()
        nav("networks")
        onNodeWithTag("network-page-proxy-edit").performClick()
        onNodeWithTag("proxy-page-type-socks5").performClick()
        onNodeWithTag("proxy-page-host").performTextReplacement(host)
        onNodeWithTag("proxy-page-port").performTextReplacement(port)
        if (user.isNotEmpty()) onNodeWithTag("proxy-page-user").performTextReplacement(user)
        if (password.isNotEmpty()) typePassword(password)
        onNodeWithTag("proxy-page-save").performClick()
    }

    /** The hidden field exposes only SetText, so this drives that action rather than text input. */
    fun ComposeUiTest.typePassword(text: String) {
        onNodeWithTag("proxy-page-password").performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(text)) }
    }

    private fun leaks(
        node: SemanticsNode,
        value: String,
    ): Boolean = node.config.any { it.value.toString().contains(value) } || node.children.any { leaks(it, value) }

    fun ComposeUiTest.treeHas(value: String) = leaks(onRoot(useUnmergedTree = true).fetchSemanticsNode(), value)
}
