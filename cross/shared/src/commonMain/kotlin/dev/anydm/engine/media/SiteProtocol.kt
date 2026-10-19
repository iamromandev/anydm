package dev.anydm.engine.media

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.Format
import dev.anydm.engine.core.RequestContext
import dev.anydm.engine.core.partialPath
import dev.anydm.engine.core.targetPath
import dev.anydm.engine.core.toStepFailed
import dev.anydm.engine.http.HttpProbe
import dev.anydm.engine.http.HttpProtocol
import dev.anydm.engine.http.hostOf
import dev.anydm.engine.http.originOf
import dev.anydm.engine.platform.FileResult
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
import dev.anydm.engine.protocol.TransferFile
import dev.anydm.engine.protocol.TransferIo
import dev.anydm.engine.protocol.TransferPlan
import dev.anydm.engine.store.Extraction
import dev.anydm.engine.store.ExtractionStore
import dev.anydm.engine.tools.ResolvedTool
import dev.anydm.engine.tools.Tool
import kotlinx.io.files.Path
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

private val EXTRACTION_TTL = 1.hours
private val CREDENTIAL_HEADERS = setOf("authorization", "proxy-authorization", "cookie")
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
    private val fragments = FragmentFetcher(http, fileSystem)

    override suspend fun probe(request: ProbeRequest): StepResult<ProbeResult> {
        val tool = resolve(Tool.YT_DLP) ?: return missing(Tool.YT_DLP)
        val url = request.mirror.url
        val deno =
            if (isYoutube(url)) resolve(Tool.DENO) ?: return missing(Tool.DENO) else null
        val previous = extractions.get(request.download.id)?.takeIf { it.url == url }
        val pinned = previous?.chosen.orEmpty()
        val stored = previous?.takeIf { clock.now() - it.extractedAt < EXTRACTION_TTL }
        // Stored addresses can have expired within the hour: one fresh extraction is tried before giving up.
        if (stored != null) {
            val built = build(request, stored.json, pinned)
            if (built !is StepResult.Failed || (built.errorClass != ErrorClass.AUTH && built.errorClass != ErrorClass.GONE)) return built
        }
        val json =
            when (val extracted = extractFresh(tool, deno, url)) {
                is StepResult.Failed -> return extracted
                is StepResult.Ok -> extracted.value
            }
        val built = build(request, json, pinned)
        if (built is StepResult.Ok) {
            val chosen =
                built.value.selection.streams
                    .map { it.formatId }
            extractions.put(Extraction(request.download.id, url, json, clock.now(), chosen))
        }
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

    /** Reads [json], chooses the streams and probes or lists each one by its route. */
    private suspend fun build(
        request: ProbeRequest,
        json: String,
        pinned: List<String>,
    ): StepResult<SiteProbe> {
        val info =
            when (val parsed = YtDlpJson.parse(json)) {
                is MediaParse.Rejected -> return StepResult.Failed(null, parsed.reason)
                is MediaParse.Ok -> parsed.info
            }
        val format = request.format ?: Format()
        val selection =
            when (val choice = FormatSelector.choose(info, format, pinned)) {
                is Choice.NoMatch -> return StepResult.Failed(null, choice.reason)
                is Choice.Ok -> choice.selection
            }
        if (selection.needsFfmpeg && resolve(Tool.FFMPEG) == null) return missing(Tool.FFMPEG)
        val probes = LinkedHashMap<String, HttpProbe>()
        val fragments = LinkedHashMap<String, List<FragmentRef>>()
        var route = selection.route
        if (route != StreamRoute.YTDLP) {
            for (stream in selection.streams) {
                val context = contextFor(request.context, stream, request.mirror.url)
                if (stream.route == StreamRoute.PLAIN) {
                    when (val probed = http.probeUrl(stream.url, context, credentials = true)) {
                        is StepResult.Failed -> return probed
                        is StepResult.Ok -> probes[stream.formatId] = probed.value
                    }
                } else {
                    when (val listed = resolveFragments(stream, context)) {
                        is StepResult.Failed -> {
                            return listed
                        }

                        is StepResult.Ok -> {
                            val resolved = listed.value
                            if (resolved == null) {
                                // An encrypted playlist: yt-dlp's downloader takes the whole job.
                                route = StreamRoute.YTDLP
                                probes.clear()
                                fragments.clear()
                                break
                            }
                            fragments[stream.formatId] = resolved
                        }
                    }
                }
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
                size = if (route == StreamRoute.PLAIN) totalSize(probes.values.toList()) else null,
                validator = if (validators.all { it == null }) null else validators.joinToString("|") { it ?: "-" },
                nameFields = nameFieldsOf(info, selection),
                fragments = fragments,
                route = route,
            ),
        )
    }

    /**
     * The fragments of a FRAGMENTS [stream]: an HLS playlist is fetched and read, a DASH list came with the page. Null
     * means the stream is encrypted, which only yt-dlp's downloader can take.
     */
    private suspend fun resolveFragments(
        stream: MediaStream,
        context: RequestContext,
    ): StepResult<List<FragmentRef>?> {
        if (!stream.protocol.startsWith("m3u8")) return StepResult.Ok(stream.fragments)
        val fetched =
            when (val got = http.fetchText(stream.url, context)) {
                is StepResult.Failed -> return got
                is StepResult.Ok -> got.value
            }
        val host = hostOf(stream.url)
        return when (val parsed = HlsPlaylist.parse(fetched.text, fetched.finalUrl)) {
            is HlsParse.Media -> StepResult.Ok(parsed.fragments)
            is HlsParse.Encrypted -> StepResult.Ok(null)
            HlsParse.Live -> StepResult.Failed(null, "the playlist at $host is a live stream, which isn't supported")
            HlsParse.Master -> StepResult.Failed(null, "the playlist at $host lists other playlists, not media")
            is HlsParse.Invalid -> StepResult.Failed(null, "can't read the playlist at $host: ${parsed.reason}")
        }
    }

    /**
     * The request's own context plus the headers the site wants for [stream] (one HTTP can't send is left out). The
     * request's credentials, its Authorization and cookies, belong to the page's own host: a stream on another host
     * gets none of them, only what the site named for that stream.
     */
    private fun contextFor(
        base: RequestContext?,
        stream: MediaStream,
        pageUrl: String,
    ): RequestContext {
        val own = stream.headers.filter { header -> runCatching { RequestContext(listOf(header)) }.isSuccess }
        val samePlace = originOf(stream.url) == originOf(pageUrl)
        val inherited =
            base?.headers.orEmpty().let { all ->
                if (samePlace) {
                    all
                } else {
                    all.filterNot {
                        it.first.lowercase() in
                            CREDENTIAL_HEADERS
                    }
                }
            }
        return RequestContext(inherited + own, base?.referrer, base?.userAgent, if (samePlace) base?.cookies else null)
    }

    override fun plan(
        probe: ProbeResult,
        context: PlanContext,
    ): StepResult<TransferPlan> {
        check(probe is SiteProbe) { "SiteProtocol can't plan a ${probe::class.simpleName}" }
        if (probe.route == StreamRoute.YTDLP) return StepResult.Failed(null, "yt-dlp's own downloader isn't wired in yet")
        val selection = probe.selection
        val outputName = context.download.fileName ?: probe.name
        val stem = outputName.substringBeforeLast('.', outputName)
        val files =
            selection.streams.mapIndexed { index, stream ->
                val path = if (selection.needsFfmpeg) "$stem.f${safeId(stream.formatId)}.${stream.ext}" else outputName
                if (stream.route == StreamRoute.PLAIN) {
                    http.planFile(
                        probe.probes.getValue(stream.formatId),
                        index,
                        path,
                        context.stored.firstOrNull { it.file.index == index },
                    )
                } else {
                    PlannedFile(index = index, path = path)
                }
            }
        return StepResult.Ok(TransferPlan(files))
    }

    /** The addresses a transfer fetches from: replaced as a whole when the page is read again. */
    private class Sources(
        val probes: Map<String, HttpProbe>,
        val fragments: Map<String, List<FragmentRef>>,
    )

    /**
     * Each stream in turn: a plain one through the HTTP engine from its own address, a fragmented one fragment by
     * fragment into a folder and then joined into its planned file. A refusal (403, 410) means the addresses expired
     * (MED-06): the page is read again, once for the whole call, and each stream carries on from what it has, if it is
     * the same stream of the same size or fragment count.
     */
    override suspend fun transfer(
        transfer: Transfer,
        io: TransferIo,
    ): StepResult<Unit> {
        val site = transfer.probe
        check(site is SiteProbe) { "SiteProtocol can't transfer a ${site::class.simpleName}" }
        if (site.route == StreamRoute.YTDLP) return StepResult.Failed(null, "yt-dlp's own downloader isn't wired in yet")
        var sources = Sources(site.probes, site.fragments)
        var refreshed = false
        var finished = 0L
        for ((index, stream) in site.selection.streams.withIndex()) {
            val file = transfer.files.single { it.file.index == index }
            while (true) {
                val later = transfer.files.filter { it.file.index > index }.sumOf { other -> other.segments.sumOf { it.written } }
                val sub = ProgressOffsetIo(io, finished + later)
                val result =
                    if (stream.route == StreamRoute.PLAIN) {
                        val own = transfer.mirror.copy(url = stream.url)
                        http.transferFile(sources.probes.getValue(stream.formatId), file, own, emptyList(), sub)
                    } else {
                        transferFragments(transfer, site, stream, file, sources, sub)
                    }
                val expired = result is StepResult.Failed && (result.errorClass == ErrorClass.AUTH || result.errorClass == ErrorClass.GONE)
                if (!expired || refreshed) {
                    if (result is StepResult.Failed) return result
                    finished += sub.last
                    break
                }
                refreshed = true
                sources =
                    when (val fresh = refresh(transfer.download.id, site, sources)) {
                        is StepResult.Failed -> return fresh
                        is StepResult.Ok -> fresh.value
                    }
            }
        }
        return StepResult.Ok(Unit)
    }

    private suspend fun transferFragments(
        transfer: Transfer,
        site: SiteProbe,
        stream: MediaStream,
        file: TransferFile,
        sources: Sources,
        io: TransferIo,
    ): StepResult<Unit> {
        val list = sources.fragments.getValue(stream.formatId)
        val folder =
            fragmentFolder(transfer, site)?.let { Path(it, safeId(stream.formatId)) } ?: return StepResult.Failed(null, "no save folder")
        val context = contextFor(site.context, stream, site.url)
        when (val got = fragments.fetch(folder, list, context, io)) {
            is StepResult.Failed -> return got
            is StepResult.Ok -> Unit
        }
        return when (val joined = fragments.join(folder, list.size, file.file.id, io)) {
            is StepResult.Failed -> joined
            is StepResult.Ok -> StepResult.Ok(Unit)
        }
    }

    /** The page read again: the same streams at their new addresses, or a failure when they are not the same. */
    private suspend fun refresh(
        id: DownloadId,
        site: SiteProbe,
        old: Sources,
    ): StepResult<Sources> {
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
        val probes = LinkedHashMap<String, HttpProbe>()
        val lists = LinkedHashMap<String, List<FragmentRef>>()
        for (before in site.selection.streams) {
            val stream =
                info.streams.firstOrNull { it.formatId == before.formatId && it.route == before.route && !it.drm }
                    ?: return StepResult.Failed(null, "the video changed while it was downloading: format ${before.formatId} is gone")
            val context = contextFor(site.context, stream, site.url)
            if (stream.route == StreamRoute.PLAIN) {
                val probe =
                    when (val probed = http.probeUrl(stream.url, context, credentials = true)) {
                        is StepResult.Failed -> return probed
                        is StepResult.Ok -> probed.value
                    }
                if (probe.size != old.probes.getValue(before.formatId).size) {
                    return StepResult.Failed(null, "the video changed while it was downloading: format ${before.formatId} has another size")
                }
                probes[before.formatId] = probe
            } else {
                val list =
                    when (val resolved = resolveFragments(stream, context)) {
                        is StepResult.Failed -> return resolved
                        is StepResult.Ok -> resolved.value
                    }
                        ?: return StepResult.Failed(
                            null,
                            "the video changed while it was downloading: format ${before.formatId} is now encrypted",
                        )
                if (list.size != old.fragments.getValue(before.formatId).size) {
                    return StepResult.Failed(
                        null,
                        "the video changed while it was downloading: format ${before.formatId} has another number of fragments",
                    )
                }
                lists[before.formatId] = list
            }
        }
        extractions.put(Extraction(id, site.url, json, clock.now(), site.selection.streams.map { it.formatId }))
        return StepResult.Ok(Sources(probes, lists))
    }

    /**
     * A stream that is already the output needs nothing. Otherwise ffmpeg writes the output's partial from the streams'
     * partials; the runner then places it and, once the job is complete, drops the consumed streams and the fragment
     * folder. A failed merge keeps them, so a retry doesn't download anything again.
     */
    override suspend fun finalize(transfer: Transfer): StepResult<FinalizeResult> {
        val site = transfer.probe
        check(site is SiteProbe) { "SiteProtocol can't finalize a ${site::class.simpleName}" }
        if (site.route == StreamRoute.YTDLP) return StepResult.Failed(null, "yt-dlp's own downloader isn't wired in yet")
        val files = transfer.files.sortedBy { it.file.index }
        if (!site.selection.needsFfmpeg) {
            extractions.delete(transfer.download.id)
            return StepResult.Ok(FinalizeResult(files.map { it.file.path }))
        }
        val ffmpeg = resolve(Tool.FFMPEG) ?: return missing(Tool.FFMPEG)
        val folder = saveFolderOf(transfer) ?: return StepResult.Failed(null, "${files.first().target} has no save folder")
        // The streams stay until the job is complete, so the merge needs room for the output on top of them.
        val needed = files.sumOf { it.file.size ?: (fileSystem.size(it.partial) as? FileResult.Ok)?.value ?: 0L }
        when (val free = fileSystem.freeSpace(folder)) {
            is FileResult.Failed -> {
                return free.toStepFailed()
            }

            is FileResult.Ok -> {
                if (free.value < needed) {
                    return StepResult.Failed(ErrorClass.DISK_FULL, "not enough room to merge the streams: $needed bytes needed")
                }
            }
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
        val cleanup = if (site.fragments.isEmpty()) emptyList() else listOf(fragmentFolderName(outputName, transfer.download.id))
        return StepResult.Ok(FinalizeResult(listOf(outputName), consumed = files.map { it.file.path }, cleanup = cleanup))
    }

    /** The save folder: the first planned file's target, up as many folders as its relative path is deep. */
    private fun saveFolderOf(transfer: Transfer): Path? {
        val first = transfer.files.minByOrNull { it.file.index } ?: return null
        var folder: Path = first.target
        repeat(first.file.path.count { it == '/' } + 1) { folder = folder.parent ?: return null }
        return folder
    }

    /** The hidden folder one job's fragments are kept in, beside its output. */
    private fun fragmentFolder(
        transfer: Transfer,
        site: SiteProbe,
    ): Path? {
        val folder = saveFolderOf(transfer) ?: return null
        return targetPath(folder, fragmentFolderName(transfer.download.fileName ?: site.name, transfer.download.id))
    }

    private fun missing(tool: Tool) = StepResult.Failed(ErrorClass.TOOL_MISSING, tool.id)

    private fun safeId(formatId: String) = formatId.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("")

    private fun isYoutube(url: String): Boolean {
        val host = hostOf(url).substringBefore(':').lowercase()
        return YOUTUBE_HOSTS.any { host == it || host.endsWith(".$it") }
    }
}

/** `.<name without extension>.<download id>.frag`, beside the output (in its folder when the output name has one). */
internal fun fragmentFolderName(
    outputName: String,
    id: DownloadId,
): String {
    val folder = outputName.substringBeforeLast('/', "")
    val name = ".${outputName.substringAfterLast('/').substringBeforeLast('.')}.${id.value}.frag"
    return if (folder.isEmpty()) name else "$folder/$name"
}
