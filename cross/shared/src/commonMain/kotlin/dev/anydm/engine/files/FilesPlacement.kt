package dev.anydm.engine.files

import dev.anydm.engine.core.DirectPlacement
import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.Placement
import dev.anydm.engine.core.PlacementPlan
import dev.anydm.engine.core.ProfileId
import dev.anydm.engine.core.effectiveCategory
import dev.anydm.engine.core.effectiveSaveFolder
import dev.anydm.engine.core.effectiveTemplate
import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.platform.FileSystem
import dev.anydm.engine.protocol.ProbeResult
import dev.anydm.engine.protocol.StepResult
import kotlinx.io.files.Path

/**
 * The real [Placement]. The folder is the job's own, else its category's, else the default one (FIL-01). The name is
 * the job's own, else the category's template rendered over what a download knows, else the probe's; then it is cleaned
 * for the OS (FIL-02, FIL-03). With [ClashPolicy.SKIP] a name that is already taken ends the plan (FIL-04); the other
 * two policies decide when the file is placed.
 */
class FilesPlacement(
    private val cleaner: NameCleaner,
    private val categories: suspend () -> List<Category>,
    private val policy: suspend () -> ClashPolicy,
    private val fileSystem: FileSystem,
    /** A job's profile, looked up for each plan so an edit reaches the next one (SET-02); null if it is gone. */
    private val profiles: suspend (ProfileId) -> Profile? = { null },
) : Placement {
    // The engine's own tool files go where their job says and replace an older copy: no category, no clash policy.
    private val tools = DirectPlacement(fileSystem, replace = true)

    override suspend fun plan(
        download: Download,
        mirror: Mirror,
        probe: ProbeResult,
        defaultFolder: Path,
        firstPrepare: Boolean,
    ): StepResult<PlacementPlan> {
        if (download.kind == DownloadKind.TOOL) return tools.plan(download, mirror, probe, defaultFolder, firstPrepare)
        val all = categories()
        val profile = download.profileId?.let { profiles(it) }
        val categoryId = effectiveCategory(download, profile)
        val category = all.firstOrNull { it.id == categoryId } ?: categoryFor(all, download, mirror, probe)
        val folder = effectiveSaveFolder(download, profile)?.let(::Path) ?: category?.folder?.let(::Path) ?: defaultFolder
        val name = cleaner.clean(download.fileName ?: templated(category, profile, probe) ?: probe.name)
        if (firstPrepare && policy() == ClashPolicy.SKIP) {
            when (val found = fileSystem.size(Path(folder, name))) {
                is FileResult.NotFound -> Unit
                is FileResult.Ok, is FileResult.AlreadyExists -> return StepResult.Failed(null, "skipped: $name already exists in $folder")
                is FileResult.Failed -> return StepResult.Failed(null, "can't check whether $name exists in $folder: ${found.detail}")
            }
        }
        return StepResult.Ok(PlacementPlan(category?.id ?: download.categoryId, folder, name))
    }

    override suspend fun place(
        download: Download,
        partial: Path,
        target: Path,
    ): FileResult<Path> =
        if (download.kind == DownloadKind.TOOL) {
            tools.place(download, partial, target)
        } else {
            placeByPolicy(partial, target)
        }

    private suspend fun placeByPolicy(
        partial: Path,
        target: Path,
    ): FileResult<Path> =
        when (policy()) {
            ClashPolicy.OVERWRITE -> {
                moved(partial, target, replace = true)
            }

            ClashPolicy.SKIP -> {
                val skipped = moved(partial, target, replace = false)
                if (skipped is FileResult.AlreadyExists && skipped.path == target) {
                    FileResult.AlreadyExists(target, "skipped: ${target.name} already exists")
                } else {
                    skipped
                }
            }

            ClashPolicy.RENAME -> {
                renamed(partial, target)
            }
        }

    /** Takes the first free name; another job can take it between the check and the move, so it tries again. */
    private suspend fun renamed(
        partial: Path,
        target: Path,
    ): FileResult<Path> {
        val folder = checkNotNull(target.parent) { "target has no folder: $target" }
        var candidate = target
        repeat(MAX_RENAMES) {
            val result = moved(partial, candidate, replace = false)
            if (result !is FileResult.AlreadyExists || result.path != candidate) return result
            candidate =
                when (val next = nextFreeName(fileSystem, folder, target.name)) {
                    is FileResult.Ok -> next.value
                    is FileResult.Failed -> return next
                }
        }
        return FileResult.AlreadyExists(target, "no free name found after $MAX_RENAMES tries")
    }

    /** One move; across volumes it copies, checks and deletes the source (FIL-07). */
    private suspend fun moved(
        partial: Path,
        target: Path,
        replace: Boolean,
    ): FileResult<Path> {
        val moved =
            when (val atomic = fileSystem.atomicMove(partial, target, replace)) {
                is FileResult.CrossVolume -> moveAcrossVolumes(fileSystem, partial, target, replace)
                else -> atomic
            }
        return when (moved) {
            is FileResult.Ok -> FileResult.Ok(target)
            is FileResult.Failed -> moved
        }
    }

    private fun categoryFor(
        all: List<Category>,
        download: Download,
        mirror: Mirror,
        probe: ProbeResult,
    ): Category? {
        val facts = SourceFacts(cleaner.clean(download.fileName ?: probe.name), probe.mime, hostOf(mirror.url), download.kind)
        return CategoryMatcher(all).match(facts)
    }

    /** The template's stem plus the probe name's extension; null when there is no template or it renders nothing. */
    private fun templated(
        category: Category?,
        profile: Profile?,
        probe: ProbeResult,
    ): String? {
        val template = effectiveTemplate(profile, category?.template) ?: return null
        val parsed = (NameTemplate.parse(template) as? TemplateParse.Ok)?.template ?: return null
        // A template with placeholders but nothing to put in them would name every file after its literal text.
        if (parsed.hasFields && !parsed.usesAnyOf(probe.nameFields)) return null
        val stem = parsed.render(probe.nameFields).takeIf { it.isNotBlank() } ?: return null
        val dot = probe.name.lastIndexOf('.')
        return if (dot > 0) stem + probe.name.substring(dot) else stem
    }

    private companion object {
        const val MAX_RENAMES = 100
    }

    private fun hostOf(url: String): String? =
        url
            .substringAfter("://", "")
            .substringBefore('/')
            .substringBefore('?')
            .substringAfterLast('@')
            .substringBefore(':')
            .takeIf { it.isNotEmpty() }
}
