package dev.anydm.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import dev.anydm.engine.store.RuntimeSettings
import dev.anydm.engine.tools.ResolvedTool
import dev.anydm.engine.tools.Tool
import dev.anydm.engine.tools.ToolStatus
import dev.anydm.engine.tools.ToolUpdate
import dev.anydm.engine.tools.ToolVersion
import dev.anydm.engine.tools.YtDlpChannel
import kotlinx.io.files.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
internal class ToolsScreenTest : PanelScreenTest(Section.TOOLS) {
    private fun managed(
        tool: Tool,
        version: String,
    ) = ToolStatus(tool, ResolvedTool(tool, ToolVersion.parse(version)!!, tool.executables.associateWith { Path("/app/tools/$it") }, true))

    @Test
    fun `it shows each tool with its version and where it came from`() {
        source.toolList = listOf(managed(Tool.YT_DLP, "2026.10.01"), ToolStatus(Tool.FFMPEG, null), ToolStatus(Tool.DENO, null))
        show()
        tag("tool-title-yt-dlp").assertTextContains("yt-dlp 2026.10.01")
        tag("tool-origin-yt-dlp").assertTextContains("kept up to date", substring = true)
        tag("tool-path-yt-dlp").assertTextContains("/app/tools/yt-dlp")
        tag("tool-origin-ffmpeg").assertTextContains("Not installed", substring = true)
        rule.onNodeWithTag("tool-update-ffmpeg").assertTextContains("Download")
        rule.onNodeWithTag("tool-update-yt-dlp").assertTextContains("Update")
    }

    @Test
    fun `update asks the engine and says what it started`() {
        source.toolList = listOf(managed(Tool.YT_DLP, "2026.10.01"))
        show()
        click("tool-update-yt-dlp")
        rule.waitForIdle()
        assertEquals(listOf("updateTool yt-dlp"), source.calls)
        tag("tools-message").assertTextContains("Fetching yt-dlp", substring = true)
        source.toolUpdate = ToolUpdate.UP_TO_DATE
        click("tool-update-yt-dlp")
        rule.waitForIdle()
        tag("tools-message").assertTextContains("yt-dlp is up to date.")
    }

    @Test
    fun `a folder that exists is sent when applied and one that does not is flagged`() {
        val folder = createTempDirectory("anydm-tools").toFile()
        try {
            show()
            type("tool-folder-deno", "/no/such/folder")
            assertEquals(emptyList(), source.calls)
            click("tool-apply-deno")
            rule.waitForIdle()
            tag("tool-folder-deno-error").assertTextContains("doesn't exist", substring = true)
            rule.onNodeWithTag("tool-folder-deno").assertTextContains("/no/such/folder")
            assertEquals(emptyMap(), source.runtime.toolFolders)
            type("tool-folder-deno", folder.path)
            click("tool-apply-deno")
            rule.waitForIdle()
            assertEquals(mapOf(Tool.DENO to folder.path), source.runtime.toolFolders)
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun `typing keeps the text and sends nothing until applied`() {
        show()
        shown("tool-folder-ffmpeg").performTextInput("/o")
        rule.waitForIdle()
        rule.onNodeWithTag("tool-folder-ffmpeg").assertTextContains("/o")
        assertEquals(emptyList(), source.calls)
    }

    @Test
    fun `update is only offered for a copy anydm keeps`() {
        source.toolList =
            listOf(
                ToolStatus(Tool.YT_DLP, null),
                managed(Tool.FFMPEG, "7.0.2"),
                ToolStatus(
                    Tool.DENO,
                    ResolvedTool(Tool.DENO, ToolVersion.parse("2.1.4")!!, mapOf("deno" to Path("/usr/bin/deno")), false),
                ),
            )
        show()
        rule.onNodeWithTag("tool-update-yt-dlp").assertIsEnabled()
        rule.onNodeWithTag("tool-update-ffmpeg").assertIsEnabled()
        rule.onNodeWithTag("tool-update-deno").assertIsNotEnabled()
    }

    @Test
    fun `choose picks a folder`() {
        val folder = createTempDirectory("anydm-tools").toFile()
        try {
            pickers.chosenFolder = folder.toPath()
            show()
            click("tool-choose-ffmpeg")
            rule.waitForIdle()
            assertEquals(folder.path, source.runtime.toolFolders[Tool.FFMPEG])
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun `the channel and the system copies reach the engine`() {
        source.runtime = RuntimeSettings(ytDlpChannel = YtDlpChannel.STABLE)
        show()
        rule.onNodeWithTag("tools-channel-stable").assertIsSelected()
        click("tools-channel-nightly")
        rule.waitForIdle()
        click("tools-system")
        rule.waitForIdle()
        assertEquals(listOf("setYtDlpChannel NIGHTLY", "setSystemTools false"), source.calls)
        rule.onNodeWithTag("tools-channel-nightly").assertIsSelected()
    }
}
