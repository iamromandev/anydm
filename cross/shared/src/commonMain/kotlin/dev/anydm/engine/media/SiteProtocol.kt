package dev.anydm.engine.media

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.Format
import dev.anydm.engine.core.RequestContext
import dev.anydm.engine.core.partialPath
import dev.anydm.engine.core.targetPath
import dev.anydm.engine.http.HttpProbe
import dev.anydm.engine.http.HttpProtocol
import dev.anydm.engine.http.hostOf
import dev.anydm.engine.platform.FileSystem
import dev.anydm.engine.platform.ProcessOutcome
import dev.anydm.engine.platform.ProcessRunner
import dev.anydm.engine.platform.ProcessSpec
import dev.anydm.engine.protocol.FinalizeResult
import dev.anydm.engine.protocol.PlanContext
import dev.anydm.engine.protocol.PlannedFile
import dev.anydm.engine.protocol.ProbeRequest
import dev.anydm.engine.protocol.ProbeResult
import dev.anydm.engine.protocol.Protocol
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.Transfer
import dev.anydm.engine.protocol.TransferIo
import dev.anydm.engine.protocol.TransferPlan
import dev.anydm.engine.store.Extraction
import dev.anydm.engine.store.ExtractionStore
import dev.anydm.engine.tools.ResolvedTool
import dev.anydm.engine.tools.Tool
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

private val EXTRACTION_TTL = 1.hours
private val YOUTUBE_HOSTS = listOf("youtube.com", "youtu.be", "youtube-nocookie.com")

/**
 * The protocol of a SITE job (MED-01 to MED-11): yt-dlp extracts, the HTTP engine downloads the chosen plain streams,
 * ffmpeg merges them. [resolve] finds a tool (the tool manager's, in the app); [beforeExtract] runs before each fresh
 * extraction, where the caller hooks the yt-dlp update check.
 */
class SiteProtocol(
    private val http: HttpProtocol,
    private val runner: ProcessRunner,
    private val resolve: suspend (Tool) -> ResolvedTool?,
    private val extractions: ExtractionStore,
    private val fileSystem: FileSystem,
    private val clock: Clock = Clock.System,
    private val beforeExtract: suspend () -> Unit = {},
) : Protocol {
    private val ytDlp = YtDlpRunner(runner)

    override suspend fun probe(request: ProbeRequest): StepResult<ProbeResult> {
        val tool = resolve(Tool.YT_DLP) ?: return missing(Tool.YT_DLP)
        val url = request.mirror.url
        val deno =
            if (isYoutube(url)) resolve(Tool.DENO) ?: return missing(Tool.DENO) else null
        val stored = extractions.get(request.download.id)?.takeIf { it.url == url && clock.now() - it.extractedAt < EXTRACTION_TTL }
        // Stored addresses can have expired within the hour: one fresh extraction is tried before giving up.
        if (stored != null) {
            val built = build(request, stored.json)
            if (built !is StepResult.Failed || (built.errorClass != ErrorClass.AUTH && built.errorClass != ErrorClass.GONE)) return built
        }
        val json =
            when (val extracted = extractFresh(tool, deno, url)) {
                is StepResult.Failed -> return extracted
                is StepResult.Ok -> extracted.value
            }
        val built = build(request, json)
        if (built is StepResult.Ok) extractions.put(Extraction(request.download.id, url, json, clock.now()))
        return built
    }

    private suspend fun extractFresh(
        tool: ResolvedTool,
        deno: ResolvedTool?,
        url: String,
    ): StepResult<String> {
        beforeExtract()
        val extracted = ytDlp.extract(tool, deno, url)
        return if (extracted is StepResult.Ok && extracted.value.isEmpty()) {
            StepResult.Failed(null, "yt-dlp printed no JSON for this page")
        } else {
            extracted
        }
    }

    /** Reads [json], chooses the streams and probes each one. */
    private suspend fun build(
        request: ProbeRequest,
        json: String,
    ): StepResult<SiteProbe> {
        val info =
            when (val parsed = YtDlpJson.parse(json)) {
                is MediaParse.Rejected -> return StepResult.Failed(null, parsed.reason)
                is MediaParse.Ok -> parsed.info
            }
        val format = request.format ?: Format()
        val selection =
            when (val choice = FormatSelector.choose(info, format)) {
                is Choice.NoMatch -> return StepResult.Failed(null, choice.reason)
                is Choice.Ok -> choice.selection
            }
        if (selection.needsFfmpeg && resolve(Tool.FFMPEG) == null) return missing(Tool.FFMPEG)
        val probes = LinkedHashMap<String, HttpProbe>()
        for (stream in selection.streams) {
            when (val probed = http.probeUrl(stream.url, contextFor(request.context, stream), credentials = true)) {
                is StepResult.Failed -> return probed
                is StepResult.Ok -> probes[stream.formatId] = probed.value
            }
        }
        val validators = probes.values.map { it.validator }
        return StepResult.Ok(
            SiteProbe(
                info = info,
                selection = selection,
                format = format,
                url = request.mirror.url,
                context = request.context,
                probes = probes,
                name = fileStem(info.title, info.id) + "." + selection.outputExtension,
                size = totalSize(probes.values.toList()),
                validator = if (validators.all { it == null }) null else validators.joinToString("|") { it ?: "-" },
                nameFields = nameFieldsOf(info, selection),
            ),
        )
    }

    /** The request's own context plus the headers the site wants for [stream]; one HTTP can't send is left out. */
    private fun contextFor(
        base: RequestContext?,
        stream: MediaStream,
    ): RequestContext {
        val extra = stream.headers.filter { header -> runCatching { RequestContext(listOf(header)) }.isSuccess }
        return RequestContext(base?.headers.orEmpty() + extra, base?.referrer, base?.userAgent, base?.cookies)
    }

    override fun plan(
        probe: ProbeResult,
        context: PlanContext,
    ): StepResult<TransferPlan> {
        check(probe is SiteProbe) { "SiteProtocol can't plan a ${probe::class.simpleName}" }
        val selection = probe.selection
        val outputName = context.download.fileName ?: probe.name
        val stem = outputName.substringBeforeLast('.', outputName)
        val files =
            selection.streams.mapIndexed { index, stream ->
                val path = if (selection.needsFfmpeg) "$stem.f${safeId(stream.formatId)}.${stream.ext}" else outputName
                val streamProbe = probe.probes.getValue(stream.formatId)
                val planned = http.planFile(streamProbe, index, path, context.stored.firstOrNull { it.file.index == index })
                planned
            }
        return StepResult.Ok(TransferPlan(files))
    }

    /**
     * Each stream in turn through the HTTP engine, from its own address. A refusal (403, 410) means the address expired
     * (MED-06): the page is read again, once for the whole call, and each stream continues from its watermarks at the
     * new address, if it is the same stream of the same size.
     */
    override suspend fun transfer(
        transfer: Transfer,
        io: TransferIo,
    ): StepResult<Unit> {
        val site = transfer.probe
        check(site is SiteProbe) { "SiteProtocol can't transfer a ${site::class.simpleName}" }
        var probes: Map<String, HttpProbe> = site.probes
        var refreshed = false
        for ((index, stream) in site.selection.streams.withIndex()) {
            val file = transfer.files.single { it.file.index == index }
            while (true) {
                val others = bytesOfOthers(transfer, index)
                val sub = transfer.mirror.copy(url = stream.url)
                val result = http.transferFile(probes.getValue(stream.formatId), file, sub, emptyList(), ProgressOffsetIo(io, others))
                val expired = result is StepResult.Failed && (result.errorClass == ErrorClass.AUTH || result.errorClass == ErrorClass.GONE)
                if (!expired || refreshed) {
                    if (result is StepResult.Failed) return result
                    break
                }
                refreshed = true
                probes =
                    when (val fresh = refresh(transfer.download.id, site)) {
                        is StepResult.Failed -> return fresh
                        is StepResult.Ok -> fresh.value
                    }
            }
        }
        return StepResult.Ok(Unit)
    }

    /** The bytes of every file but [index]: the earlier ones are whole, the later ones as far as they were stored. */
    private fun bytesOfOthers(
        transfer: Transfer,
        index: Int,
    ): Long =
        transfer.files.filter { it.file.index != index }.sumOf { other ->
            if (other.file.index < index) other.file.size ?: other.segments.sumOf { it.written } else other.segments.sumOf { it.written }
        }

    /** The page read again: the same streams at their new addresses, probed, or a failure when they are not the same. */
    private suspend fun refresh(
        id: DownloadId,
        site: SiteProbe,
    ): StepResult<Map<String, HttpProbe>> {
        val tool = resolve(Tool.YT_DLP) ?: return missing(Tool.YT_DLP)
        val deno = if (isYoutube(site.url)) resolve(Tool.DENO) ?: return missing(Tool.DENO) else null
        val json =
            when (val extracted = extractFresh(tool, deno, site.url)) {
                is StepResult.Failed -> return extracted
                is StepResult.Ok -> extracted.value
            }
        val info =
            when (val parsed = YtDlpJson.parse(json)) {
                is MediaParse.Rejected -> return StepResult.Failed(null, parsed.reason)
                is MediaParse.Ok -> parsed.info
            }
        val fresh = LinkedHashMap<String, HttpProbe>()
        for (old in site.selection.streams) {
            val stream =
                info.streams.firstOrNull { it.formatId == old.formatId && it.isPlain }
                    ?: return StepResult.Failed(null, "the video changed while it was downloading: format ${old.formatId} is gone")
            val probe =
                when (val probed = http.probeUrl(stream.url, contextFor(site.context, stream), credentials = true)) {
                    is StepResult.Failed -> return probed
                    is StepResult.Ok -> probed.value
                }
            if (probe.size != site.probes.getValue(old.formatId).size) {
                return StepResult.Failed(null, "the video changed while it was downloading: format ${old.formatId} has another size")
            }
            fresh[old.formatId] = probe
        }
        extractions.put(Extraction(id, site.url, json, clock.now()))
        return StepResult.Ok(fresh)
    }

    /**
     * A stream that is already the output needs nothing. Otherwise ffmpeg writes the output's partial from the streams'
     * partials; the runner then places it and, once the job is complete, drops the consumed streams. A failed merge
     * keeps the streams, so a retry doesn't download them again.
     */
    override suspend fun finalize(transfer: Transfer): StepResult<FinalizeResult> {
        val site = transfer.probe
        check(site is SiteProbe) { "SiteProtocol can't finalize a ${site::class.simpleName}" }
        val files = transfer.files.sortedBy { it.file.index }
        if (!site.selection.needsFfmpeg) return StepResult.Ok(FinalizeResult(files.map { it.file.path }))
        val ffmpeg = resolve(Tool.FFMPEG) ?: return missing(Tool.FFMPEG)
        val first = files.first()
        var folder = first.target
        repeat(first.file.path.count { it == '/' } + 1) {
            folder =
                folder.parent ?: return StepResult.Failed(null, "${first.target} has no save folder")
        }
        val outputName = transfer.download.fileName ?: site.name
        val output = partialPath(targetPath(folder, outputName), transfer.download.id)
        output.parent?.let { fileSystem.prepareFolder(it) }
        val args = Ffmpeg.args(ffmpeg.executable(Tool.FFMPEG.id), files.map { it.partial }, output, site.selection)
        val ran =
            when (val outcome = runner.run(ProcessSpec(args))) {
                is ProcessOutcome.Failed -> return StepResult.Failed(ErrorClass.TOOL_MISSING, Tool.FFMPEG.id)
                is ProcessOutcome.Ok -> outcome.value
            }
        if (ran.exitCode != 0) {
            fileSystem.delete(output)
            val reason =
                ran.stderr
                    .lineSequence()
                    .lastOrNull { it.isNotBlank() }
                    .orEmpty()
            return StepResult.Failed(null, "ffmpeg failed (${ran.exitCode}): $reason".trim())
        }
        extractions.delete(transfer.download.id)
        return StepResult.Ok(FinalizeResult(listOf(outputName), consumed = files.map { it.file.path }))
    }

    private fun missing(tool: Tool) = StepResult.Failed(ErrorClass.TOOL_MISSING, tool.id)

    private fun safeId(formatId: String) = formatId.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("")

    private fun isYoutube(url: String): Boolean {
        val host = hostOf(url).substringBefore(':').lowercase()
        return YOUTUBE_HOSTS.any { host == it || host.endsWith(".$it") }
    }
}
