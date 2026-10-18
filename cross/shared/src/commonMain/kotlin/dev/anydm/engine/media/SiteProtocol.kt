package dev.anydm.engine.media

import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.Format
import dev.anydm.engine.core.RequestContext
import dev.anydm.engine.http.HttpProbe
import dev.anydm.engine.http.HttpProtocol
import dev.anydm.engine.http.hostOf
import dev.anydm.engine.platform.FileSystem
import dev.anydm.engine.platform.ProcessRunner
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
    runner: ProcessRunner,
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

    override suspend fun transfer(
        transfer: Transfer,
        io: TransferIo,
    ): StepResult<Unit> = StepResult.Failed(null, "not yet")

    override suspend fun finalize(transfer: Transfer): StepResult<FinalizeResult> = StepResult.Failed(null, "not yet")

    private fun missing(tool: Tool) = StepResult.Failed(ErrorClass.TOOL_MISSING, tool.id)

    private fun safeId(formatId: String) = formatId.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("")

    private fun isYoutube(url: String): Boolean {
        val host = hostOf(url).substringBefore(':').lowercase()
        return YOUTUBE_HOSTS.any { host == it || host.endsWith(".$it") }
    }
}
