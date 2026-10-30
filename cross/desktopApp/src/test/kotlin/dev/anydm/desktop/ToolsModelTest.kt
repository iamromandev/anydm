package dev.anydm.desktop

import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.store.RuntimeSettings
import dev.anydm.engine.tools.ResolvedTool
import dev.anydm.engine.tools.Tool
import dev.anydm.engine.tools.ToolStatus
import dev.anydm.engine.tools.ToolUpdate
import dev.anydm.engine.tools.ToolVersion
import dev.anydm.engine.tools.YtDlpChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolsModelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val source = FakeSource()
    private val folders = mutableSetOf("/opt/ff")

    private fun model() = ToolsModel(source, scope, { it in folders }, Dispatchers.Unconfined).also { it.load() }

    private fun found(
        tool: Tool,
        version: String,
        managed: Boolean,
        folder: String = "/usr/bin",
    ) = ToolStatus(
        tool,
        ResolvedTool(tool, ToolVersion.parse(version)!!, tool.executables.associateWith { Path(folder, it) }, managed),
    )

    @AfterTest
    fun close() = scope.cancel()

    @Test
    fun `it lists every tool with where its copy comes from`() {
        source.toolList =
            listOf(
                found(Tool.YT_DLP, "2026.10.01", managed = true, folder = "/app/tools"),
                found(Tool.FFMPEG, "7.0.2", managed = false, folder = "/opt/ff"),
                ToolStatus(Tool.DENO, null),
            )
        source.runtime = RuntimeSettings(toolFolders = mapOf(Tool.FFMPEG to "/opt/ff"))
        val rows =
            model()
                .state.value.tools
                .associateBy { it.tool }
        assertEquals(ToolOrigin.MANAGED, rows.getValue(Tool.YT_DLP).origin)
        assertEquals("2026.10.01", rows.getValue(Tool.YT_DLP).version)
        assertEquals(ToolOrigin.FOLDER, rows.getValue(Tool.FFMPEG).origin)
        assertEquals("/opt/ff", rows.getValue(Tool.FFMPEG).folder)
        assertEquals(ToolOrigin.MISSING, rows.getValue(Tool.DENO).origin)
        assertNull(rows.getValue(Tool.DENO).version)
    }

    @Test
    fun `a copy found on the machine is a system copy`() {
        source.toolList = listOf(found(Tool.DENO, "2.1.4", managed = false))
        assertEquals(
            ToolOrigin.SYSTEM,
            model()
                .state.value.tools
                .single { it.tool == Tool.DENO }
                .origin,
        )
    }

    @Test
    fun `an update says what came of it`() {
        val model = model()
        model.update(Tool.YT_DLP)
        assertEquals(listOf("updateTool yt-dlp"), source.calls)
        assertTrue(
            model.state.value.message
                .orEmpty()
                .contains("downloads list"),
        )

        source.toolUpdate = ToolUpdate.UP_TO_DATE
        model.update(Tool.YT_DLP)
        assertEquals("yt-dlp is up to date.", model.state.value.message)

        source.toolUpdate = ToolUpdate.UNAVAILABLE
        model.update(Tool.DENO)
        assertTrue(
            model.state.value.message
                .orEmpty()
                .contains("No build of Deno"),
        )
    }

    @Test
    fun `the channel and the system copies switch through the engine`() {
        val model = model()
        model.setChannel(YtDlpChannel.NIGHTLY)
        model.setSystemTools(false)
        assertEquals(YtDlpChannel.NIGHTLY, model.state.value.channel)
        assertEquals(false, model.state.value.systemTools)
        assertEquals(listOf("setYtDlpChannel NIGHTLY", "setSystemTools false"), source.calls)
    }

    @Test
    fun `a folder is kept when it exists and is absolute`() {
        val model = model()
        model.setFolder(Tool.FFMPEG, " /opt/ff ")
        assertEquals(mapOf(Tool.FFMPEG to "/opt/ff"), source.runtime.toolFolders)
        assertNull(model.state.value.folderErrors[Tool.FFMPEG])
    }

    @Test
    fun `typing a folder changes only the draft until it is applied`() {
        val model = model()
        "/opt/f".forEach { c ->
            model.editFolder(
                Tool.FFMPEG,
                (
                    model.state.value.folderDrafts[Tool.FFMPEG]
                        .orEmpty()
                ) + c,
            )
        }
        assertEquals("/opt/f", model.state.value.folderDrafts[Tool.FFMPEG])
        assertTrue(source.calls.isEmpty())
        model.editFolder(Tool.FFMPEG, "/opt/ff")
        model.applyFolder(Tool.FFMPEG)
        assertEquals(mapOf(Tool.FFMPEG to "/opt/ff"), source.runtime.toolFolders)
        assertNull(model.state.value.folderDrafts[Tool.FFMPEG])
    }

    @Test
    fun `a refused draft keeps its text and says why`() {
        val model = model()
        model.editFolder(Tool.FFMPEG, "/nope")
        model.applyFolder(Tool.FFMPEG)
        assertEquals("/nope", model.state.value.folderDrafts[Tool.FFMPEG])
        assertEquals("That folder doesn't exist.", model.state.value.folderErrors[Tool.FFMPEG])
        model.editFolder(Tool.FFMPEG, "/opt")
        assertNull(model.state.value.folderErrors[Tool.FFMPEG])
    }

    @Test
    fun `a folder is only the origin when the copy is inside it`() {
        source.toolList = listOf(found(Tool.FFMPEG, "7.0.2", managed = false, folder = "/opt/ffmpeg/bin"))
        source.runtime = RuntimeSettings(toolFolders = mapOf(Tool.FFMPEG to "/opt/ff"))
        assertEquals(
            ToolOrigin.SYSTEM,
            model()
                .state.value.tools
                .single()
                .origin,
        )
    }

    @Test
    fun `a folder that is relative or missing is refused before the engine hears of it`() {
        val model = model()
        model.setFolder(Tool.FFMPEG, "bin")
        assertEquals("Use a full folder path.", model.state.value.folderErrors[Tool.FFMPEG])
        model.setFolder(Tool.FFMPEG, "/nope")
        assertEquals("That folder doesn't exist.", model.state.value.folderErrors[Tool.FFMPEG])
        assertTrue(source.calls.isEmpty())
    }

    @Test
    fun `an empty folder goes back to the usual search`() {
        source.runtime = RuntimeSettings(toolFolders = mapOf(Tool.FFMPEG to "/opt/ff"))
        val model = model()
        model.setFolder(Tool.FFMPEG, "")
        assertEquals(emptyMap(), source.runtime.toolFolders)
        assertEquals(listOf("setToolFolder ffmpeg -"), source.calls)
    }

    @Test
    fun `a folder without the tool in it says the usual search is used`() {
        source.toolList = listOf(ToolStatus(Tool.FFMPEG, null))
        val model = model()
        model.setFolder(Tool.FFMPEG, "/opt/ff")
        assertTrue(
            model.state.value.message
                .orEmpty()
                .contains("ffmpeg, ffprobe"),
            model.state.value.message,
        )
    }

    @Test
    fun `a refused change says why`() {
        source.results["setToolFolder ffmpeg /opt/ff"] = CommandResult.Invalid("not absolute")
        val model = model()
        model.setFolder(Tool.FFMPEG, "/opt/ff")
        assertEquals("not absolute", model.state.value.message)
    }

    @Test
    fun `a stopped engine is reported`() {
        val model = model()
        source.refuseAll = true
        model.update(Tool.DENO)
        assertEquals("The download engine has stopped, so nothing was changed.", model.state.value.message)
    }
}
