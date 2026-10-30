package dev.anydm.engine.tools

import dev.anydm.engine.core.Checksum
import dev.anydm.engine.core.ChecksumAlgorithm
import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.platform.AppDirs
import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.platform.FileSystem
import dev.anydm.engine.platform.ProcessOutcome
import dev.anydm.engine.platform.ProcessRunner
import dev.anydm.engine.platform.ProcessSpec
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.Settings
import dev.anydm.engine.store.ToolStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** A tool that can be run: where each of its executables is, and whether the engine installed it itself. */
data class ResolvedTool(
    val tool: Tool,
    val version: ToolVersion,
    /** By executable id (`ffmpeg`, `ffprobe`), not file name. */
    val executables: Map<String, Path>,
    val managed: Boolean,
) {
    fun executable(name: String): Path = getValue(name)

    private fun getValue(name: String): Path = requireNotNull(executables[name]) { "${tool.id} has no executable $name" }
}

/** A tool and the copy in use; [resolved] null when none is installed. */
data class ToolStatus(
    val tool: Tool,
    val resolved: ResolvedTool?,
)

/** What asking for an update came to (#609). */
enum class ToolUpdate { FETCHING, UP_TO_DATE, UNAVAILABLE }

/**
 * Keeps yt-dlp, ffmpeg and Deno available (BIN-01..BIN-04). A tool is found as a copy already on the machine (when
 * allowed and new enough) or as the engine's own under `<cache>/tools/<tool>/<version>`. One that is missing is fetched
 * by TOOL jobs the manager adds through the `add` it is handed; when the last of a tool's files has arrived,
 * [installed] records it, and only then does it become current, so a version that fails its self-test never replaces
 * the one that works.
 */
class ToolManager(
    private val catalog: ToolCatalog,
    private val store: ToolStore,
    private val runner: ProcessRunner,
    private val fileSystem: FileSystem,
    private val finder: PathFinder,
    private val releases: ReleaseSource,
    dirs: AppDirs,
    private val platform: Platform,
    private val settings: suspend () -> Settings,
    private val clock: Clock,
    /** Every TOOL job the engine holds, so a fetch already running (even from before a restart) isn't started twice. */
    private val toolJobs: suspend () -> List<Download>,
) {
    private val root = Path(dirs.cache, "tools")
    private val lock = Mutex()

    // TOOL jobs whose finish [installed] has seen. A COMPLETED job counts as fetching until then, so a request that
    // lands between the job's last save and its finish hook doesn't start a second one.
    private val processed = mutableSetOf<DownloadId>()

    private fun names(tool: Tool) = tool.executables.associateWith { tool.fileName(it, platform.os) }

    /** The tool ready to run, or null when it isn't installed (or not new enough). */
    suspend fun resolve(tool: Tool): ResolvedTool? {
        val settings = settings()
        // A folder the user named comes first, and holds even with system copies switched off: they chose it.
        settings.toolFolders[tool]?.let { folder -> inFolder(tool, folder)?.let { return it } }
        if (settings.systemTools) systemCopy(tool)?.let { return it }
        return managedCopy(tool)
    }

    private suspend fun inFolder(
        tool: Tool,
        folder: Path,
    ): ResolvedTool? {
        val names = names(tool)
        val found = findExecutables(fileSystem, folder, names.values.toSet())
        return versioned(tool, names.mapValues { (_, file) -> found[file] ?: return null })
    }

    private suspend fun systemCopy(tool: Tool): ResolvedTool? =
        versioned(tool, names(tool).mapValues { (_, file) -> finder.find(file) ?: return null })

    // Runs each executable for its version: the tool is as old as its oldest part, and too old is not found.
    private suspend fun versioned(
        tool: Tool,
        paths: Map<String, Path>,
    ): ResolvedTool? {
        var lowest: ToolVersion? = null
        for ((_, path) in paths) {
            val ran = withTimeoutOrNull(VERSION_LIMIT) { runner.run(ProcessSpec(listOf(path.toString(), tool.versionArgument))) }
            val result = (ran as? ProcessOutcome.Ok)?.value ?: return null
            val version = ToolVersion.fromOutput(tool, result.stdout)?.takeIf { result.exitCode == 0 } ?: return null
            if (version < catalog.minimum(tool)) return null
            if (lowest == null || version < lowest) lowest = version
        }
        return ResolvedTool(tool, lowest ?: return null, paths, managed = false)
    }

    private suspend fun managedCopy(tool: Tool): ResolvedTool? {
        val record = store.get(tool) ?: return null
        val version = ToolVersion.parse(record.version) ?: return null
        val names = names(tool)
        val found = findExecutables(fileSystem, Path(record.path), names.values.toSet())
        val executables = names.mapValues { (_, file) -> found[file] ?: return null }
        return ResolvedTool(tool, version, executables, managed = true)
    }

    /**
     * Makes sure [tool] is on its way: true when it is installed, or a TOOL job for it is running (also one from before a restart) or was
     * just added; false when there is nothing to fetch for this platform or the job couldn't be added. A job that failed or was
     * cancelled doesn't count, so the next call tries again. Many callers at once add one job.
     */
    suspend fun ensure(
        tool: Tool,
        add: suspend (NewDownload) -> CommandResult,
    ): Boolean =
        lock.withLock {
            if (resolve(tool) != null) return true
            if (stillFetching(tool)) return true
            val plan = planFor(tool) ?: return false
            fetch(tool, plan, add)
        }

    private suspend fun stillFetching(tool: Tool): Boolean {
        val folder = Path(root, tool.id)
        return toolJobs().any { job ->
            job.saveFolder?.let { Path(it).parent } == folder &&
                when (job.status) {
                    DownloadStatus.FAILED, DownloadStatus.CANCELLED -> false
                    DownloadStatus.COMPLETED -> job.id !in processed
                    else -> true
                }
        }
    }

    private class Plan(
        val version: String,
        val artifacts: List<Artifact>,
    )

    private suspend fun planFor(tool: Tool): Plan? =
        if (tool == Tool.YT_DLP) {
            releases
                .latest(
                    settings().ytDlpChannel,
                    platform,
                )?.let { Plan(it.version, listOf(Artifact(it.url, it.sha256, ArchiveKind.NONE))) }
        } else {
            catalog.entryFor(tool, platform)?.let { Plan(it.version, it.artifacts) }
        }

    private suspend fun fetch(
        tool: Tool,
        plan: Plan,
        add: suspend (NewDownload) -> CommandResult,
    ): Boolean {
        val folder = Path(root, tool.id, plan.version).toString()
        for (artifact in plan.artifacts) {
            // A bare binary is saved under its own executable name; finalize moves it there.
            val fileName =
                if (artifact.archive ==
                    ArchiveKind.NONE
                ) {
                    tool.fileName(tool.executables.first(), platform.os)
                } else {
                    artifact.fileName
                }
            val job =
                NewDownload(
                    DownloadKind.TOOL,
                    mirrors = listOf(artifact.url),
                    saveFolder = folder,
                    fileName = fileName,
                    checksum = Checksum(ChecksumAlgorithm.SHA256, artifact.sha256),
                    allowDuplicate = true,
                )
            if ((add(job) as? CommandResult.Done)?.download == null) return false
        }
        return true
    }

    /**
     * A TOOL job finished (the engine's finish hook): when every executable of its tool is now in the version folder,
     * records the version as current and removes the older ones, keeping the one before. False when the folder isn't
     * one of ours or the tool isn't whole yet (another file is still coming).
     */
    suspend fun installed(
        download: Download,
        outputs: List<Path>,
    ): Boolean =
        lock.withLock {
            processed += download.id
            val folder = download.saveFolder?.let(::Path) ?: return false
            val tool = Tool.byId(folder.parent?.name.orEmpty())
            if (tool == null || folder.parent?.parent != root) return false
            val wanted = names(tool).values.toSet()
            if (findExecutables(fileSystem, folder, wanted).size < wanted.size) return false
            val before = store.get(tool)
            store.put(ToolRecord(tool, folder.name, folder.toString(), clock.now()))
            // The same version again (a repair, or a restart) keeps the earlier one that is the way back.
            if (before?.version != folder.name) tidy(tool, keep = setOfNotNull(folder.name, before?.version))
            true
        }

    private suspend fun tidy(
        tool: Tool,
        keep: Set<String>,
    ) {
        val versions = (fileSystem.list(Path(root, tool.id)) as? FileResult.Ok)?.value ?: return
        for (version in versions) {
            if (version.name in keep || fileSystem.size(version) !is FileResult.AlreadyExists) continue
            if (version.parent == Path(root, tool.id)) fileSystem.deleteTree(version)
        }
    }

    /**
     * Before a yt-dlp extraction: when the installed copy was last checked over a day ago, looks for a newer build on
     * the channel and fetches it; the check time is recorded whenever a release could be read. Nothing happens for other
     * tools, when nothing is installed (ensure does the first install) or when a system copy is in use.
     */
    suspend fun refreshIfStale(
        tool: Tool,
        add: suspend (NewDownload) -> CommandResult,
    ) {
        if (tool != Tool.YT_DLP) return
        lock.withLock {
            val record = store.get(tool) ?: return
            if (resolve(tool)?.managed == false) return
            val checked = record.checkedAt
            if (checked != null && clock.now() - checked < STALE_AFTER) return
            val release = releases.latest(settings().ytDlpChannel, platform) ?: return
            store.put(record.copy(checkedAt = clock.now()))
            val newer = ToolVersion.parse(release.version)?.let { it > (ToolVersion.parse(record.version) ?: return@let false) } == true
            if (newer &&
                !stillFetching(tool)
            ) {
                fetch(tool, Plan(release.version, listOf(Artifact(release.url, release.sha256, ArchiveKind.NONE))), add)
            }
        }
    }

    /**
     * The user asks for the newest build of [tool] (#609): yt-dlp's latest on its channel, the others the catalog's pinned
     * version. Fetches it unless the installed one is already that version or newer, or a fetch is under way.
     */
    suspend fun update(
        tool: Tool,
        add: suspend (NewDownload) -> CommandResult,
    ): ToolUpdate =
        lock.withLock {
            if (stillFetching(tool)) return ToolUpdate.FETCHING
            val plan = planFor(tool) ?: return ToolUpdate.UNAVAILABLE
            val record = store.get(tool)
            if (tool == Tool.YT_DLP && record != null) store.put(record.copy(checkedAt = clock.now()))
            val have = record?.let { ToolVersion.parse(it.version) }
            val want = ToolVersion.parse(plan.version)
            if (have != null && want != null && have >= want && managedCopy(tool) != null) return ToolUpdate.UP_TO_DATE
            if (fetch(tool, plan, add)) ToolUpdate.FETCHING else ToolUpdate.UNAVAILABLE
        }

    private companion object {
        val VERSION_LIMIT = 10.seconds
        val STALE_AFTER = 24.hours
    }
}
