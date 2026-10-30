package dev.anydm.desktop

import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.tools.Tool
import dev.anydm.engine.tools.ToolUpdate
import dev.anydm.engine.tools.YtDlpChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/** Where the copy of a tool in use comes from. */
enum class ToolOrigin { MISSING, MANAGED, SYSTEM, FOLDER }

data class ToolRow(
    val tool: Tool,
    val version: String?,
    /** The first executable of the copy in use. */
    val path: String?,
    val origin: ToolOrigin,
    /** The folder the user named for this tool; empty for none. */
    val folder: String,
)

data class ToolsState(
    val loaded: Boolean = false,
    val tools: List<ToolRow> = emptyList(),
    val channel: YtDlpChannel = YtDlpChannel.STABLE,
    val systemTools: Boolean = true,
    val folderErrors: Map<Tool, String> = emptyMap(),
    /** What is typed in each tool's folder field and not applied yet; a tool with no draft shows its saved folder. */
    val folderDrafts: Map<Tool, String> = emptyMap(),
    val message: String? = null,
)

val Tool.label: String
    get() =
        when (this) {
            Tool.YT_DLP -> "yt-dlp"
            Tool.FFMPEG -> "ffmpeg"
            Tool.DENO -> "Deno"
        }

// A path is inside a folder when it is under it as a path, not just starting with the same letters (/opt/ffmpeg is not in /opt/ff).
private fun insideFolder(
    path: String,
    folder: String,
): Boolean = runCatching { Path.of(path).startsWith(Path.of(folder)) }.getOrDefault(false)

/**
 * The runtime tools section (#609): what yt-dlp, ffmpeg and Deno are installed, a button to fetch the newest, the yt-dlp
 * channel, whether copies on the machine count, and a folder to use instead. Each change goes to the engine at once; the
 * folder is typed first and applied with a button.
 */
class ToolsModel(
    private val source: EngineSource,
    private val scope: CoroutineScope,
    /** Asked on [io] only: a slow share must not stall typing. */
    private val folderExists: (String) -> Boolean = { runCatching { Files.isDirectory(Path.of(it)) }.getOrDefault(false) },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val ui = MutableStateFlow(ToolsState())
    val state: StateFlow<ToolsState> = ui.asStateFlow()

    fun load() =
        scope.launch {
            guarded { read() }
        }

    private suspend fun read() {
        val status = source.toolStatus()
        val runtime = source.runtimeSettings()
        val rows =
            status.map { s ->
                val folder = runtime.toolFolders[s.tool].orEmpty()
                val path =
                    s.resolved
                        ?.executables
                        ?.values
                        ?.firstOrNull()
                        ?.toString()
                val origin =
                    when {
                        s.resolved == null -> ToolOrigin.MISSING
                        s.resolved!!.managed -> ToolOrigin.MANAGED
                        folder.isNotEmpty() && path != null && insideFolder(path, folder) -> ToolOrigin.FOLDER
                        else -> ToolOrigin.SYSTEM
                    }
                ToolRow(s.tool, s.resolved?.version?.toString(), path, origin, folder)
            }
        ui.update { it.copy(loaded = true, tools = rows, channel = runtime.ytDlpChannel, systemTools = runtime.systemTools) }
    }

    /** Fetches the newest build of [tool]; the job it starts shows in the downloads list. */
    fun update(tool: Tool) {
        scope.launch {
            guarded {
                val text =
                    when (source.updateTool(tool)) {
                        ToolUpdate.FETCHING -> "Fetching ${tool.label}… it shows in the downloads list, and is used once it has arrived."
                        ToolUpdate.UP_TO_DATE -> "${tool.label} is up to date."
                        ToolUpdate.UNAVAILABLE -> "No build of ${tool.label} is available for this system."
                    }
                ui.update { it.copy(message = text) }
                read()
            }
        }
    }

    fun setChannel(channel: YtDlpChannel) = change({ source.setYtDlpChannel(channel) }) { it.copy(channel = channel) }

    fun setSystemTools(on: Boolean) = change({ source.setSystemTools(on) }) { it.copy(systemTools = on) }

    /** Keeps what is being typed for [tool]'s folder; nothing is sent until [applyFolder]. */
    fun editFolder(
        tool: Tool,
        text: String,
    ) = ui.update { it.copy(folderDrafts = it.folderDrafts + (tool to text), folderErrors = it.folderErrors - tool) }

    /** Applies the draft of [tool]'s folder. */
    fun applyFolder(tool: Tool) {
        val draft = ui.value.folderDrafts[tool] ?: return
        setFolder(tool, draft)
    }

    /** Uses the executables in [text] for [tool]; empty goes back to the usual search. */
    fun setFolder(
        tool: Tool,
        text: String,
    ) {
        val folder = text.trim()
        scope.launch {
            guarded {
                val error =
                    when {
                        folder.isEmpty() -> null
                        !runCatching { Path.of(folder).isAbsolute }.getOrDefault(false) -> "Use a full folder path."
                        !withContext(io) { folderExists(folder) } -> "That folder doesn't exist."
                        else -> null
                    }
                ui.update { it.copy(folderErrors = if (error == null) it.folderErrors - tool else it.folderErrors + (tool to error)) }
                if (error != null) return@guarded
                when (val result = source.setToolFolder(tool, folder.ifEmpty { null })) {
                    is CommandResult.Done -> {
                        // Text typed while this was applying stays.
                        ui.update { if (it.folderDrafts[tool]?.trim() == folder) it.copy(folderDrafts = it.folderDrafts - tool) else it }
                        read()
                        val row = ui.value.tools.firstOrNull { it.tool == tool }
                        val missing = folder.isNotEmpty() && row?.origin != ToolOrigin.FOLDER
                        ui.update {
                            it.copy(
                                message =
                                    if (missing) {
                                        "No ${tool.executables.joinToString(
                                            ", ",
                                        )} found in that folder (or too old), so the usual search is used."
                                    } else {
                                        null
                                    },
                            )
                        }
                    }

                    is CommandResult.Invalid -> {
                        ui.update { it.copy(message = result.reason) }
                    }

                    else -> {
                        ui.update { it.copy(message = result.toString()) }
                    }
                }
            }
        }
    }

    private fun change(
        command: suspend () -> CommandResult,
        apply: (ToolsState) -> ToolsState,
    ) {
        scope.launch {
            guarded {
                when (val result = command()) {
                    is CommandResult.Done -> {
                        ui.update { apply(it).copy(message = null) }
                        read()
                    }

                    is CommandResult.Invalid -> {
                        ui.update { it.copy(message = result.reason) }
                    }

                    else -> {
                        ui.update { it.copy(message = result.toString()) }
                    }
                }
            }
        }
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: IllegalStateException) {
            ui.update { it.copy(message = "The download engine has stopped, so nothing was changed.") }
        } catch (e: IllegalArgumentException) {
            ui.update { it.copy(message = e.message ?: "The engine did not accept that.") }
        }
    }
}
