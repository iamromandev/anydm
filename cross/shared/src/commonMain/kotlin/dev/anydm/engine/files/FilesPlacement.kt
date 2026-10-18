package dev.anydm.engine.files

import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.Placement
import dev.anydm.engine.core.PlacementPlan
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
) : Placement {
    override suspend fun plan(
        download: Download,
        mirror: Mirror,
        probe: ProbeResult,
        defaultFolder: Path,
    ): StepResult<PlacementPlan> {
        val category = categoryFor(download, mirror, probe)
        val folder = download.saveFolder?.let(::Path) ?: category?.folder?.let(::Path) ?: defaultFolder
        val name = cleaner.clean(download.fileName ?: templated(category, probe) ?: probe.name)
        if (policy() == ClashPolicy.SKIP) {
            when (val found = fileSystem.size(Path(folder, name))) {
                is FileResult.NotFound -> Unit
                is FileResult.Ok, is FileResult.AlreadyExists -> return StepResult.Failed(null, "skipped: $name already exists in $folder")
                is FileResult.Failed -> return StepResult.Failed(null, "can't check whether $name exists in $folder: ${found.detail}")
            }
        }
        return StepResult.Ok(PlacementPlan(category?.id ?: download.categoryId, folder, name))
    }

    private suspend fun categoryFor(
        download: Download,
        mirror: Mirror,
        probe: ProbeResult,
    ): Category? {
        val facts = SourceFacts(cleaner.clean(download.fileName ?: probe.name), probe.mime, hostOf(mirror.url), download.kind)
        return CategoryMatcher(categories()).match(facts)
    }

    /** The template's stem plus the probe name's extension; null when there is no template or it renders nothing. */
    private fun templated(
        category: Category?,
        probe: ProbeResult,
    ): String? {
        val template = category?.template ?: return null
        val parsed = NameTemplate.parse(template) as? TemplateParse.Ok ?: return null
        val stem = parsed.template.render(NameFields()).takeIf { it.isNotBlank() } ?: return null
        val dot = probe.name.lastIndexOf('.')
        return if (dot > 0) stem + probe.name.substring(dot) else stem
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
