package dev.anydm.engine.media

import dev.anydm.engine.core.CookieSource
import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.Format
import dev.anydm.engine.core.MediaSettings
import dev.anydm.engine.core.RequestContext
import dev.anydm.engine.core.SubtitleMode
import dev.anydm.engine.core.partialPath
import dev.anydm.engine.core.targetPath
import dev.anydm.engine.core.toStepFailed
import dev.anydm.engine.core.ytDlpFlags
import dev.anydm.engine.http.HttpProbe
import dev.anydm.engine.http.HttpProtocol
import dev.anydm.engine.http.hostOf
import dev.anydm.engine.http.originOf
import dev.anydm.engine.log.Log
import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.platform.FileSystem
import dev.anydm.engine.platform.ProcessFailure
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
import kotlinx.coroutines.delay
import kotlinx.io.files.Path
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val EXTRACTION_TTL = 1.hours
private val YOUTUBE_HOSTS = listOf("youtube.com", "youtu.be", "youtube-nocookie.com")
private const val EXTRA_TRIES = 3
private val EXTRA_RETRY_WAIT = 500.milliseconds
private val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa")

/** Choices only yt-dlp's downloader carries out, or that make it need ffmpeg (subtitles, SponsorBlock, embedding). */
private fun Format.hasYtDlpExtras(): Boolean = subtitles != null || sponsorBlock != null || embed.any

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
    /** The global cookie source and pacing, asked at each probe so a change applies to the next job. */
    private val mediaSettings: suspend () -> MediaSettings = { MediaSettings() },
    private val pacer: HostPacer = HostPacer(),
    private val log: Log = Log.None,
) : Protocol {
    private val ytDlp = YtDlpRunner(runner)
    private val fragments = FragmentFetcher(http, fileSystem, pacer)
    private val ytDlpDownload = YtDlpDownload(runner)

    /** The result of reading a page: a probe, no stream that fits the choice, or another failure. */
    private sealed interface Built {
        data class Ok(
            val probe: SiteProbe,
        ) : Built

        data class NoMatch(
            val failure: StepResult.Failed,
        ) : Built

        data class Failed(
            val failure: StepResult.Failed,
        ) : Built
    }

    private fun Built.result(): StepResult<SiteProbe> =
        when (this) {
            is Built.Ok -> StepResult.Ok(probe)
            is Built.NoMatch -> failure
            is Built.Failed -> failure
        }

    override suspend fun probe(request: ProbeRequest): StepResult<ProbeResult> {
        val tool = resolve(Tool.YT_DLP) ?: return missing(Tool.YT_DLP)
        val url = request.mirror.url
        val deno =
            if (isYoutube(url)) resolve(Tool.DENO) ?: return missing(Tool.DENO) else null
        val settings = mediaSettings()
        val cookiesWanted = settings.cookieSource != CookieSource.None
        val previous = extractions.get(request.download.id)?.takeIf { it.url == url }
        val pinned = previous?.chosen.orEmpty()
        val stored = previous?.takeIf { clock.now() - it.extractedAt < EXTRACTION_TTL }
        // Stored addresses can have expired within the hour: one fresh extraction is tried before giving up.
        if (stored != null) {
            val built = build(request, stored.json, pinned, withCookies = cookiesWanted && !stored.withoutCookies)
            if (built is Built.Ok ||
                (built is Built.Failed && built.failure.errorClass != ErrorClass.AUTH && built.failure.errorClass != ErrorClass.GONE)
            ) {
                return built.result()
            }
        }
        var extracted =
            when (val got = extractFresh(tool, deno, url, extractionArgs(settings, withCookies = cookiesWanted))) {
                is StepResult.Failed -> return got
                is StepResult.Ok -> got.value
            }
        var built = build(request, extracted.json, pinned, withCookies = cookiesWanted)
        var anonymous = false
        // The site may hold formats back from a logged-in session (MED-19): look once without the cookies.
        if (cookiesWanted && (built is Built.NoMatch || (built is Built.Ok && extracted.hinted))) {
            val retry = extractFresh(tool, deno, url, extractionArgs(settings, withCookies = false))
            val rebuilt = (retry as? StepResult.Ok)?.let { build(request, it.value.json, pinned, withCookies = false) }
            if (rebuilt is Built.Ok && (built !is Built.Ok || heightOf(rebuilt) > heightOf(built))) {
                built = rebuilt
                extracted = (retry as StepResult.Ok).value
                anonymous = true
            } else if (built is Built.NoMatch) {
                return StepResult.Failed(
                    ErrorClass.FORMAT_WITHHELD,
                    "the site withheld the formats this choice needs; the account may lack access",
                )
            }
        }
        if (built is Built.Ok) {
            val chosen =
                built.probe.selection.streams
                    .map { it.formatId }
            extractions.put(Extraction(request.download.id, url, withoutSessionCookies(extracted.json), clock.now(), chosen, anonymous))
        }
        return built.result()
    }

    private fun heightOf(built: Built.Ok): Int =
        built.probe.selection.video
            ?.height ?: 0

    /** The cookie and pacing arguments for yt-dlp. */
    private fun extractionArgs(
        settings: MediaSettings,
        withCookies: Boolean,
    ): List<String> = (if (withCookies) settings.cookieSource.ytDlpFlags() else emptyList()) + settings.pacing.values().ytDlpFlags()

    private suspend fun extractFresh(
        tool: ResolvedTool,
        deno: ResolvedTool?,
        url: String,
        extraArgs: List<String>,
    ): StepResult<Extracted> {
        beforeExtract()
        return when (val extracted = ytDlp.extract(tool, deno, url, extraArgs)) {
            is StepResult.Failed -> {
                extracted
            }

            is StepResult.Ok -> {
                if (extracted.value.json.isEmpty()) {
                    StepResult.Failed(
                        null,
                        "yt-dlp printed no JSON for this page",
                    )
                } else {
                    extracted
                }
            }
        }
    }

    /** Reads [json], chooses the streams and probes or lists each one by its route. */
    private suspend fun build(
        request: ProbeRequest,
        json: String,
        pinned: List<String>,
        withCookies: Boolean,
    ): Built {
        val info =
            when (val parsed = YtDlpJson.parse(json)) {
                is MediaParse.Rejected -> return Built.Failed(StepResult.Failed(null, parsed.reason))
                is MediaParse.Ok -> parsed.info
            }
        val format = request.format ?: Format()
        val selection =
            when (val choice = FormatSelector.choose(info, format, pinned)) {
                is Choice.NoMatch -> return Built.NoMatch(StepResult.Failed(null, choice.reason))
                is Choice.Ok -> choice.selection
            }
        val extras = extrasOf(info, format)
        if ((selection.needsFfmpeg || selection.audioFormat != null || extras.any || format.hasYtDlpExtras()) &&
            resolve(Tool.FFMPEG) == null
        ) {
            return Built.Failed(missing(Tool.FFMPEG))
        }
        val probes = LinkedHashMap<String, HttpProbe>()
        val fragments = LinkedHashMap<String, List<FragmentRef>>()
        var route = selection.route
        if (route != StreamRoute.YTDLP) {
            for (stream in selection.streams) {
                val context = contextFor(request.context, stream, request.mirror.url)
                if (stream.route == StreamRoute.PLAIN) {
                    when (val probed = http.probeUrl(stream.url, context, credentials = true)) {
                        is StepResult.Failed -> return Built.Failed(probed)
                        is StepResult.Ok -> probes[stream.formatId] = probed.value
                    }
                } else {
                    when (val listed = resolveFragments(stream, context)) {
                        is StepResult.Failed -> {
                            return Built.Failed(listed)
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
        return Built.Ok(
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
                nameFields = nameFieldsOf(info, selection, request.download.playlistIndex),
                fragments = fragments,
                route = route,
                extras = extras,
                withCookies = withCookies,
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
    ): RequestContext =
        contextForUrl(
            base,
            stream.url,
            pageUrl,
            stream.headers.filter { header ->
                runCatching { RequestContext(listOf(header)) }.isSuccess
            },
        )

    /** The same for any address on the page ([own] are headers named for it): logins only to the page's own host. */
    private fun contextForUrl(
        base: RequestContext?,
        url: String,
        pageUrl: String,
        own: List<Pair<String, String>> = emptyList(),
    ): RequestContext {
        val samePlace = originOf(url) == originOf(pageUrl)
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
        val selection = probe.selection
        val outputName = context.download.fileName ?: probe.name
        val stem = outputName.substringBeforeLast('.', outputName)
        // yt-dlp's downloader makes the output itself: one planned file stands for it, and finalize replaces its partial.
        if (probe.route == StreamRoute.YTDLP) return StepResult.Ok(TransferPlan(listOf(PlannedFile(index = 0, path = "$stem.ytdlp"))))
        val files =
            selection.streams.mapIndexed { index, stream ->
                val path = if (probe.mainNeedsFfmpeg) "$stem.f${safeFormatId(stream.formatId)}.${stream.ext}" else outputName
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
        // The subtitle tracks and the cover are not planned: the page can list others on a later read, and the plan must not move.
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
        if (site.route == StreamRoute.YTDLP) return transferWithYtDlp(transfer, site, io)
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
        fetchExtras(transfer, site, io)?.let { return it }
        return StepResult.Ok(Unit)
    }

    /**
     * The subtitle tracks and the cover, each into a file of the job's extras folder (one already there from an earlier
     * run is kept). They are extras: one that can't be had (missing, a web page, empty) is logged and left out, never
     * failing the video, and a passing error (a timeout, a 429) is tried again a few times. Only a full disk fails the job.
     */
    private suspend fun fetchExtras(
        transfer: Transfer,
        site: SiteProbe,
        io: TransferIo,
    ): StepResult.Failed? {
        val wanted =
            site.extras.subtitles.mapIndexed { i, track -> Triple("subtitle ${track.lang}", track.url, subtitleFileName(i, track)) } +
                listOfNotNull(site.extras.thumbnail?.let { Triple("cover picture", it.thumbnail.url, coverFileName(it)) })
        if (wanted.isEmpty()) return null
        val folder = extrasFolder(transfer, site) ?: return StepResult.Failed(null, "no save folder")
        val made = fileSystem.prepareFolder(folder)
        if (made is FileResult.Failed) return made.toStepFailed()
        val gap =
            mediaSettings()
                .pacing
                .values()
                .requestSleep.seconds
        for ((label, url, name) in wanted) {
            val path = Path(folder, name)
            if (hasBytes(path)) continue
            var tries = 0
            while (true) {
                pacer.pace(originOf(url), gap)
                val got = http.fetchFragment(url, contextForUrl(site.context, url, site.url), null, null, io)
                if (got is StepResult.Ok) {
                    // Whole or not there: a crash in the middle of the write must not leave a short subtitle to be used.
                    val part = Path(folder, "$name.part")
                    writeFile(part, got.value)?.let { return it }
                    val placed = fileSystem.atomicMove(part, path, replace = true)
                    if (placed is FileResult.Failed) return placed.toStepFailed()
                    break
                }
                val failure = got as StepResult.Failed
                tries++
                val passing = failure.errorClass == ErrorClass.TRANSIENT || failure.errorClass == ErrorClass.RATE_LIMITED
                if (!passing || tries >= EXTRA_TRIES) {
                    log.warn("media") { "$label skipped: ${failure.message}" }
                    break
                }
                delay(EXTRA_RETRY_WAIT * tries)
            }
        }
        return null
    }

    /** yt-dlp downloads and merges the whole selection into a staging folder, held to the job's share of the limit. */
    private suspend fun transferWithYtDlp(
        transfer: Transfer,
        site: SiteProbe,
        io: TransferIo,
    ): StepResult<Unit> {
        val ytDlpTool = resolve(Tool.YT_DLP) ?: return missing(Tool.YT_DLP)
        val deno = if (isYoutube(site.url)) resolve(Tool.DENO) ?: return missing(Tool.DENO) else null
        val ffmpeg = resolve(Tool.FFMPEG)
        if ((site.selection.needsFfmpeg || site.selection.audioFormat != null || site.format.hasYtDlpExtras()) && ffmpeg == null) {
            return missing(Tool.FFMPEG)
        }
        val accessArgs = extractionArgs(mediaSettings(), withCookies = site.withCookies)
        val staging = stagingFolder(transfer, site) ?: return StepResult.Failed(null, "no save folder")
        val made = fileSystem.prepareFolder(staging)
        if (made is FileResult.Failed) return made.toStepFailed()
        // What yt-dlp's files already hold from earlier runs is not new: its first report after a resume includes it.
        val seed =
            (fileSystem.list(staging) as? FileResult.Ok)
                ?.value
                .orEmpty()
                .filterNot { it.name.endsWith(".ytdl") }
                .mapNotNull { file ->
                    val size = (fileSystem.size(file) as? FileResult.Ok)?.value ?: return@mapNotNull null
                    file.toString().removeSuffix(".part") to size
                }.toMap()
        return ytDlpDownload.download(
            { limit ->
                YtDlpCommand.download(
                    ytDlpTool.executable(Tool.YT_DLP.id),
                    ffmpeg?.executable(Tool.FFMPEG.id),
                    deno?.executable(Tool.DENO.id),
                    site.selection,
                    staging,
                    site.url,
                    limit,
                    site.format,
                    accessArgs,
                )
            },
            io,
            seed,
        )
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
            fragmentFolder(transfer, site)?.let { Path(it, safeFormatId(stream.formatId)) }
                ?: return StepResult.Failed(null, "no save folder")
        val context = contextFor(site.context, stream, site.url)
        val gap =
            mediaSettings()
                .pacing
                .values()
                .requestSleep.seconds
        when (val got = fragments.fetch(folder, list, context, io, trustedUrl = stream.url, gap = gap)) {
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
        val settings = mediaSettings()
        val cookies = settings.cookieSource != CookieSource.None
        val json =
            when (val extracted = extractFresh(tool, deno, site.url, extractionArgs(settings, withCookies = site.withCookies))) {
                is StepResult.Failed -> return extracted
                is StepResult.Ok -> extracted.value.json
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
        extractions.put(
            Extraction(
                id,
                site.url,
                withoutSessionCookies(json),
                clock.now(),
                site.selection.streams.map { it.formatId },
                cookies && !site.withCookies,
            ),
        )
        return StepResult.Ok(Sources(probes, lists))
    }

    /**
     * A stream that is already the output needs nothing. When [SiteProbe.mainNeedsFfmpeg], ffmpeg writes the output's
     * partial from the streams' partials, with the subtitle tracks, chapters, cover and metadata the choice asks for; the
     * runner then places it and, once the job is complete, drops the raw streams and the work folders. A failed merge
     * keeps them, so a retry doesn't download anything again. A merge that fails with extras in it is tried once more
     * without them: a subtitle ffmpeg can't read must not cost the video. Subtitles asked for as files are converted
     * from their raw files into outputs of their own; a track that can't be converted is skipped.
     */
    override suspend fun finalize(transfer: Transfer): StepResult<FinalizeResult> {
        val site = transfer.probe
        check(site is SiteProbe) { "SiteProtocol can't finalize a ${site::class.simpleName}" }
        if (site.route == StreamRoute.YTDLP) return finalizeYtDlp(transfer, site)
        val streamFiles = transfer.files.sortedBy { it.file.index }
        val folder = saveFolderOf(transfer) ?: return StepResult.Failed(null, "${streamFiles.first().target} has no save folder")
        val outputName = transfer.download.fileName ?: site.name
        val stem = outputName.substringBeforeLast('.', outputName)
        val extrasDir = extrasFolder(transfer, site)
        // An extra that was never fetched (the fetch failed) is not there, and is left out.
        val tracks =
            site.extras.subtitles.mapIndexedNotNull { i, track ->
                extrasDir?.let { Path(it, subtitleFileName(i, track)) }?.takeIf { hasBytes(it) }?.let { track to it }
            }
        val coverRaw =
            site.extras.thumbnail
                ?.let { extrasDir?.let { dir -> Path(dir, coverFileName(it)) } }
                ?.takeIf { hasBytes(it) }
        val mode = site.format.subtitles?.mode
        val embedded = if (mode == SubtitleMode.EMBED || mode == SubtitleMode.BOTH) tracks else emptyList()
        val separate = if (mode == SubtitleMode.SEPARATE || mode == SubtitleMode.BOTH) tracks else emptyList()
        val ffmpeg =
            if (site.mainNeedsFfmpeg || separate.isNotEmpty()) resolve(Tool.FFMPEG) ?: return missing(Tool.FFMPEG) else null
        val outputs = mutableListOf<String>()
        if (ffmpeg == null || !site.mainNeedsFfmpeg) {
            outputs += streamFiles.map { it.file.path }
        } else {
            val inputs = streamFiles.map { it.partial } + embedded.map { it.second } + listOfNotNull(coverRaw)
            // The raw files stay until the job is complete, so the merge needs room for the output on top of them.
            val needed =
                inputs.sumOf { input ->
                    streamFiles.firstOrNull { it.partial == input }?.file?.size ?: (fileSystem.size(input) as? FileResult.Ok)?.value ?: 0L
                }
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
            mergeMain(transfer, site, ffmpeg.executable(Tool.FFMPEG.id), streamFiles, embedded, coverRaw, folder, outputName)?.let {
                return it
            }
            outputs += outputName
        }
        if (ffmpeg != null) outputs += convertSubtitles(transfer, site, ffmpeg.executable(Tool.FFMPEG.id), separate, folder, stem, outputs)
        extractions.delete(transfer.download.id)
        val cleanup =
            listOfNotNull(
                if (site.fragments.isEmpty()) null else hiddenFolderName(outputName, transfer.download.id, "frag"),
                if (site.extras.subtitles.isEmpty() &&
                    site.extras.thumbnail == null
                ) {
                    null
                } else {
                    hiddenFolderName(outputName, transfer.download.id, "extras")
                },
            )
        return StepResult.Ok(
            FinalizeResult(outputs, consumed = streamFiles.map { it.file.path }.filter { it !in outputs }, cleanup = cleanup),
        )
    }

    /**
     * ffmpeg writes the main output's partial from the streams and the extras that came; null when it did, else the
     * failure. When it fails with extras in the command, it is run again with the metadata alone.
     */
    private suspend fun mergeMain(
        transfer: Transfer,
        site: SiteProbe,
        ffmpeg: Path,
        streamFiles: List<TransferFile>,
        embedded: List<Pair<SubtitleTrack, Path>>,
        coverRaw: Path?,
        folder: Path,
        outputName: String,
    ): StepResult.Failed? {
        val stem = outputName.substringBeforeLast('.', outputName)
        val id = transfer.download.id.value
        val cover = coverRaw?.let { targetPath(folder, "$stem.cover.$id.tmp.jpg") }
        val chapters = if (site.extras.chapters.isEmpty()) null else targetPath(folder, "$stem.chapters.$id.tmp")
        val output = partialPath(targetPath(folder, outputName), transfer.download.id)
        try {
            val coverUsable = coverRaw != null && cover != null && convertCover(ffmpeg, coverRaw, cover)
            if (coverRaw != null && !coverUsable) log.warn("media") { "cover picture skipped: ffmpeg couldn't read it" }
            val chaptersUsable = chapters != null && writeSmall(chapters, ffmetadataOf(site.extras.chapters).encodeToByteArray())
            if (chapters != null && !chaptersUsable) log.warn("media") { "chapters skipped: couldn't write the chapters file" }
            val full =
                MergeExtras(
                    embedded.map { (track, raw) -> EmbeddedSubtitle(raw, track.lang, track.ext) },
                    chapters?.takeIf { chaptersUsable },
                    cover?.takeIf { coverUsable },
                    site.extras.metadata,
                )
            output.parent?.let { fileSystem.prepareFolder(it) }
            val first = runMerge(ffmpeg, streamFiles, output, site.selection, full)
            if (first == null || full.copy(metadata = emptyList()) == MergeExtras.None) return first
            // Something in the extras may be what ffmpeg refused: the video is worth more than any of them.
            val bare = MergeExtras(metadata = site.extras.metadata)
            log.warn("media") { "merge with extras failed (${first.message}); trying without subtitles, chapters and cover" }
            return runMerge(ffmpeg, streamFiles, output, site.selection, bare)
        } finally {
            for (temp in listOfNotNull(cover, chapters)) fileSystem.delete(temp)
        }
    }

    private suspend fun runMerge(
        ffmpeg: Path,
        streamFiles: List<TransferFile>,
        output: Path,
        selection: Selection,
        extras: MergeExtras,
    ): StepResult.Failed? {
        val args = Ffmpeg.args(ffmpeg, streamFiles.map { it.partial }, output, selection, extras)
        val ran =
            when (val outcome = runner.run(ProcessSpec(args))) {
                is ProcessOutcome.Failed -> return cannotRun(outcome.failure)
                is ProcessOutcome.Ok -> outcome.value
            }
        if (ran.exitCode == 0) return null
        fileSystem.delete(output)
        val reason =
            ran.stderr
                .lineSequence()
                .lastOrNull { it.isNotBlank() }
                .orEmpty()
        return StepResult.Failed(null, "ffmpeg failed (${ran.exitCode}): $reason".trim())
    }

    /** Each raw subtitle in [separate] as a file of its own, `<stem>.<lang>[-auto].<ext>`; the names that were made. */
    private suspend fun convertSubtitles(
        transfer: Transfer,
        site: SiteProbe,
        ffmpeg: Path,
        separate: List<Pair<SubtitleTrack, Path>>,
        folder: Path,
        stem: String,
        taken: List<String>,
    ): List<String> {
        val convertTo = site.format.subtitles?.convertTo
        val made = mutableListOf<String>()
        for ((track, raw) in separate) {
            val auto = if (track.auto) "-auto" else ""
            val name = "$stem.${safeFormatId(track.lang)}$auto.${safeFormatId(convertTo?.extension ?: track.ext)}"
            if (name in taken || name in made) continue
            val out = partialPath(targetPath(folder, name), transfer.download.id)
            val args = Ffmpeg.subtitleArgs(ffmpeg, raw, out, convertTo, track.ext)
            val ran = (runner.run(ProcessSpec(args)) as? ProcessOutcome.Ok)?.value
            if (ran == null || ran.exitCode != 0) {
                fileSystem.delete(out)
                log.warn("media") { "subtitle ${track.lang} skipped: ffmpeg couldn't convert it" }
            } else {
                made += name
            }
        }
        return made
    }

    /** ffmpeg isn't there only when the system says so; a command that couldn't start is another matter. */
    private fun cannotRun(failure: ProcessFailure): StepResult.Failed =
        when (failure) {
            is ProcessFailure.NotFound -> missing(Tool.FFMPEG)
            is ProcessFailure.StartFailed -> StepResult.Failed(null, "couldn't start ffmpeg: ${failure.detail ?: "unknown reason"}")
        }

    private suspend fun hasBytes(path: Path): Boolean = ((fileSystem.size(path) as? FileResult.Ok)?.value ?: 0L) > 0L

    /** [raw] as a JPEG at [cover]; false (and nothing left behind) when ffmpeg can't. */
    private suspend fun convertCover(
        ffmpeg: Path,
        raw: Path,
        cover: Path,
    ): Boolean {
        val ran = (runner.run(ProcessSpec(Ffmpeg.thumbnailArgs(ffmpeg, raw, cover))) as? ProcessOutcome.Ok)?.value
        if (ran != null && ran.exitCode == 0) return true
        fileSystem.delete(cover)
        return false
    }

    private suspend fun writeSmall(
        path: Path,
        bytes: ByteArray,
    ): Boolean = writeFile(path, bytes) == null

    /** A small file made whole, replacing any old one: null when it is there, else the failure. */
    private suspend fun writeFile(
        path: Path,
        bytes: ByteArray,
    ): StepResult.Failed? {
        fileSystem.delete(path)
        (fileSystem.preallocate(path, bytes.size.toLong()) as? FileResult.Failed)?.let { return it.toStepFailed() }
        val writer =
            when (val opened = fileSystem.openWriter(path)) {
                is FileResult.Failed -> return opened.toStepFailed()
                is FileResult.Ok -> opened.value
            }
        return (writer.use { it.write(0, bytes) } as? FileResult.Failed)?.toStepFailed()
    }

    /** The hidden folder one job's subtitle and cover files are kept in, beside its output. */
    private fun extrasFolder(
        transfer: Transfer,
        site: SiteProbe,
    ): Path? {
        val folder = saveFolderOf(transfer) ?: return null
        return targetPath(folder, hiddenFolderName(transfer.download.fileName ?: site.name, transfer.download.id, "extras"))
    }

    /** The file yt-dlp left in its staging folder becomes the output's partial; the runner places it and removes the folder. */
    private suspend fun finalizeYtDlp(
        transfer: Transfer,
        site: SiteProbe,
    ): StepResult<FinalizeResult> {
        val folder = saveFolderOf(transfer) ?: return StepResult.Failed(null, "no save folder")
        val outputName = transfer.download.fileName ?: site.name
        val stagingName = hiddenFolderName(outputName, transfer.download.id, "ytdlp")
        val listed = fileSystem.list(targetPath(folder, stagingName))
        // Exactly the output we asked for: yt-dlp may leave other files beside it (intermediate streams, an old run's).
        val produced =
            (listed as? FileResult.Ok)?.value?.firstOrNull { it.name == "out.${site.selection.outputExtension}" }
                ?: return StepResult.Failed(null, "yt-dlp finished but left no file")
        val output = partialPath(targetPath(folder, outputName), transfer.download.id)
        output.parent?.let { fileSystem.prepareFolder(it) }
        val moved = fileSystem.atomicMove(produced, output, replace = true)
        if (moved is FileResult.Failed) return moved.toStepFailed()
        // Subtitle files yt-dlp left beside the video (`out.<lang>.<ext>`) are outputs of their own.
        val stem = outputName.substringBeforeLast('.', outputName)
        val sidecars = mutableListOf<String>()
        if (site.format.subtitles != null) {
            for (file in (listed as FileResult.Ok).value.sortedBy { it.name }) {
                val parts = file.name.split('.')
                if (parts.size != 3 || parts[0] != "out" || parts[2].lowercase() !in SUBTITLE_EXTENSIONS) continue
                val name = "$stem.${safeFormatId(parts[1])}.${parts[2].lowercase()}"
                val target = partialPath(targetPath(folder, name), transfer.download.id)
                if (fileSystem.atomicMove(file, target, replace = true) is FileResult.Ok) {
                    sidecars += name
                } else {
                    log.warn("media") { "subtitle ${parts[1]} skipped: couldn't move it" }
                }
            }
        }
        extractions.delete(transfer.download.id)
        return StepResult.Ok(
            FinalizeResult(
                listOf(outputName) + sidecars,
                consumed = transfer.files.map { it.file.path },
                cleanup = listOf(stagingName, hiddenFolderName(outputName, transfer.download.id, "frag")),
            ),
        )
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
        return targetPath(folder, hiddenFolderName(transfer.download.fileName ?: site.name, transfer.download.id, "frag"))
    }

    /** The staging folder yt-dlp downloads into, beside the output. */
    private fun stagingFolder(
        transfer: Transfer,
        site: SiteProbe,
    ): Path? {
        val folder = saveFolderOf(transfer) ?: return null
        return targetPath(folder, hiddenFolderName(transfer.download.fileName ?: site.name, transfer.download.id, "ytdlp"))
    }

    override fun workFolders(download: Download): List<String> {
        val name = download.fileName ?: return emptyList()
        return listOf(
            hiddenFolderName(name, download.id, "frag"),
            hiddenFolderName(name, download.id, "ytdlp"),
            hiddenFolderName(name, download.id, "extras"),
        )
    }

    private fun missing(tool: Tool) = StepResult.Failed(ErrorClass.TOOL_MISSING, tool.id)
}

internal fun isYoutube(url: String): Boolean {
    val host = hostOf(url).substringBefore(':').lowercase()
    return YOUTUBE_HOSTS.any { host == it || host.endsWith(".$it") }
}

/** `.<name without extension>.<download id>.<suffix>`, beside the output (in its folder when the output name has one). */
internal fun hiddenFolderName(
    outputName: String,
    id: DownloadId,
    suffix: String,
): String {
    val folder = outputName.substringBeforeLast('/', "")
    val name = ".${outputName.substringAfterLast('/').substringBeforeLast('.')}.${id.value}.$suffix"
    return if (folder.isEmpty()) name else "$folder/$name"
}

/** A format id as a folder or file name part: `..` or a leading dot would leave or hide the folder. */
internal fun safeFormatId(formatId: String): String {
    val cleaned = formatId.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("")
    return if (cleaned.isEmpty() || cleaned.startsWith(".")) "_$cleaned" else cleaned
}

/** The file name of the subtitle track [index] in a job's extras folder. */
internal fun subtitleFileName(
    index: Int,
    track: SubtitleTrack,
): String = "sub$index.${safeFormatId(track.lang)}.${safeFormatId(track.ext)}"

/** The file name of the cover picture in a job's extras folder. */
internal fun coverFileName(chosen: ChosenThumbnail): String = "cover.${safeFormatId(chosen.thumbnail.ext.ifEmpty { "img" })}"
