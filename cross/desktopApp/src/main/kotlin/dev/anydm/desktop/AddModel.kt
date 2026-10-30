package dev.anydm.desktop

import dev.anydm.engine.AddPlaylistResult
import dev.anydm.engine.AddTorrentResult
import dev.anydm.engine.InspectResult
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DuplicateReason
import dev.anydm.engine.files.Profile
import dev.anydm.engine.ftp.SshKeyLogin
import dev.anydm.engine.os.LinkIntake
import dev.anydm.engine.os.LinkProbeResult
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.torrent.TorrentInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

enum class RowStatus {
    CHECKING,
    READY,
    MEDIA,
    DUPLICATE,
    NOT_A_LINK,
    INVALID,
    FAILED,
    UNAVAILABLE,
    ADDED,
    ADD_FAILED,
}

/** One link of the add dialog and what is known about it. [link] is what gets added; [existing] is the job it duplicates. */
data class LinkRow(
    val text: String,
    val link: String?,
    val kind: DownloadKind?,
    val status: RowStatus,
    val name: String? = null,
    val size: Long? = null,
    val message: String? = null,
    val existing: DownloadId? = null,
    val addAnyway: Boolean = false,
) {
    /** A failed add is tried again by the next Add. */
    val addable: Boolean
        get() =
            status == RowStatus.READY || status == RowStatus.MEDIA || status == RowStatus.ADD_FAILED ||
                (status == RowStatus.DUPLICATE && addAnyway)
}

/** The one torrent in the add, read before it is added: which of its files to fetch, and in order or not. */
data class TorrentPick(
    val source: String,
    val info: TorrentInfo,
    val selected: Set<String>,
    val sequential: Boolean = false,
)

data class AddState(
    val text: String = "",
    val rows: List<LinkRow> = emptyList(),
    val options: AddOptions = AddOptions(),
    val profiles: List<Profile> = emptyList(),
    val torrent: TorrentPick? = null,
    val errors: Map<Field, String> = emptyMap(),
    val adding: Boolean = false,
) {
    /** Some row has an FTP, FTPS or SFTP link, so the login fields show. */
    val needsLogin: Boolean get() = rows.any { it.kind == DownloadKind.FTP }

    val canAdd: Boolean
        get() = !adding && errors.isEmpty() && rows.any { it.addable } && (torrent == null || torrent.selected.isNotEmpty())
}

// The rows a refused add's outcome is shown on: ones that can be added again as they stand now.
private val Retryable = setOf(RowStatus.READY, RowStatus.MEDIA, RowStatus.DUPLICATE)

/** How many links are read at once. */
private const val PROBES_AT_ONCE = 4

/** How many torrents are read at once, apart from the links: a magnet can wait minutes for its metadata. */
private const val INSPECTS_AT_ONCE = 2

/**
 * The add dialog (#607): the links pasted, each read in the background before it is added, the choices that apply to all
 * of them, and the add itself, which sends each link the way its kind needs. [onDone] is called when every link was added.
 */
class AddModel(
    private val source: EngineSource,
    parent: CoroutineScope,
    initial: List<String> = emptyList(),
    private val onDone: () -> Unit = {},
    private val now: () -> Instant = { Clock.System.now() },
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
    private val isFolder: (String) -> Boolean = { java.io.File(it).isDirectory },
    /** How long a link must stay as it is before it is read, so one being typed isn't read at every letter. */
    private val settle: Duration = 400.milliseconds,
) {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[kotlinx.coroutines.Job]))
    private val probes = Semaphore(PROBES_AT_ONCE)
    private val inspects = Semaphore(INSPECTS_AT_ONCE)

    // What each link probed as, by the exact string probed; read and written under [lock], as the rows are rebuilt.
    private val lock = Any()
    private val probed = mutableMapOf<String, LinkProbeResult?>()
    private val inspected = mutableMapOf<String, InspectResult?>()
    private val anyway = mutableSetOf<String>()
    private val outcomes = mutableMapOf<String, LinkRow>()
    private val picks = mutableMapOf<String, TorrentPick>()

    // The links and torrents the rows want read now; a read that waited out [settle] for one no longer here is dropped.
    private var wanted = emptySet<String>()

    // Whether a folder is one, by the text typed: asked once per text, not at every rebuild, as a network path can stall.
    private val folders = mutableMapOf<String, Boolean>()

    private val ui = MutableStateFlow(AddState(text = initial.joinToString("\n")))
    val state: StateFlow<AddState> = ui.asStateFlow()

    init {
        scope.launch { ui.update { it.copy(profiles = runCatching { source.profiles() }.getOrDefault(emptyList())) } }
        rebuild()
    }

    // The text and options change under the lock, so a rebuild never builds rows from ones already replaced.
    fun setText(text: String) {
        synchronized(lock) {
            ui.update { it.copy(text = text) }
            // What came of a line's add is forgotten when the line goes: pasted again later, it is a new link to add.
            val lines = parseInput(text).map { it.text }.toSet()
            outcomes.keys.retainAll(lines)
        }
        rebuild()
    }

    fun setOptions(change: (AddOptions) -> AddOptions) {
        synchronized(lock) { ui.update { it.copy(options = change(it.options)) } }
        rebuild()
    }

    fun setAddAnyway(
        text: String,
        on: Boolean,
    ) {
        synchronized(lock) { if (on) anyway += text else anyway -= text }
        rebuild()
    }

    fun toggleTorrentFile(path: String) =
        changePick {
            it.copy(
                selected =
                    if (path in
                        it.selected
                    ) {
                        it.selected - path
                    } else {
                        it.selected + path
                    },
            )
        }

    fun setSequential(on: Boolean) = changePick { it.copy(sequential = on) }

    private fun changePick(change: (TorrentPick) -> TorrentPick) {
        synchronized(lock) {
            val pick = ui.value.torrent ?: return
            picks[pick.source] = change(pick)
        }
        rebuild()
    }

    /** Stops every read still running; the dialog is closing. */
    fun cancel() = scope.cancel()

    /** Adds every row that can be added; each row then shows what came of it. */
    fun add() {
        var current: AddState? = null
        ui.update { state ->
            current = state.takeIf { it.canAdd }
            if (current == null) state else state.copy(adding = true)
        }
        val started = current ?: return
        scope.launch {
            try {
                for (row in started.rows.filter { it.addable }) {
                    val outcome = addOne(row, started) ?: break
                    synchronized(lock) { outcomes[row.text] = outcome }
                    rebuild()
                }
            } finally {
                ui.update { it.copy(adding = false) }
                rebuild()
            }
            val rows = state.value.rows
            if (rows.isNotEmpty() && rows.all { it.status == RowStatus.ADDED }) onDone()
        }
    }

    // The row as it stands after its add, or null when the engine has stopped and nothing more can be added.
    private suspend fun addOne(
        row: LinkRow,
        state: AddState,
    ): LinkRow? {
        val options = state.options
        val link = row.link ?: return row
        val template =
            NewDownload(
                DownloadKind.DIRECT,
                categoryId = options.categoryId,
                profileId = options.profileId,
                saveFolder = options.folder.trim().ifEmpty { null },
                startAt = startInstant(options, zone),
                allowDuplicate = row.addAnyway,
            )
        return try {
            when (row.kind) {
                DownloadKind.TORRENT -> {
                    val pick = state.torrent?.takeIf { it.source == link }
                    // Each torrent goes with what was read of it, so a magnet's metadata isn't fetched a second time.
                    val info = pick?.info ?: synchronized(lock) { (inspected[link] as? InspectResult.Inspected)?.info }
                    torrentOutcome(
                        row,
                        source.addTorrent(
                            link,
                            pick?.selected,
                            pick?.sequential ?: false,
                            template.copy(kind = DownloadKind.TORRENT),
                            info,
                        ),
                        options,
                    )
                }

                DownloadKind.SITE -> {
                    val media = template.copy(kind = DownloadKind.SITE, format = toFormat(options))
                    if (options.playlist) {
                        playlistOutcome(row, source.addPlaylist(link, selection(options), media), options)
                    } else {
                        commandOutcome(row, source.add(media.copy(mirrors = listOf(link))), options)
                    }
                }

                DownloadKind.FTP -> {
                    val url =
                        if (options.keyPath.isNotBlank()) {
                            val kept =
                                source.saveKeyLogin(
                                    link,
                                    SshKeyLogin(options.user.trim(), options.keyPath.trim(), options.passphrase.ifEmpty { null }),
                                )
                            // A key only in memory is gone after a restart, and the job with it could no longer sign in.
                            if (!kept) {
                                return row.copy(
                                    status = RowStatus.ADD_FAILED,
                                    message = "The key could not be kept in the system keychain.",
                                )
                            }
                            link
                        } else {
                            withLogin(link, options.user, options.password)
                        }
                    commandOutcome(row, source.add(template.copy(kind = DownloadKind.FTP, mirrors = listOf(url))), options)
                }

                else -> {
                    commandOutcome(row, source.add(template.copy(mirrors = listOf(link))), options)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IllegalStateException) {
            synchronized(
                lock,
            ) { outcomes[row.text] = row.copy(status = RowStatus.ADD_FAILED, message = "The download engine has stopped.") }
            null
        } catch (e: Exception) {
            row.copy(status = RowStatus.ADD_FAILED, message = "It could not be added: ${e.message ?: e::class.simpleName}")
        }
    }

    private suspend fun added(
        row: LinkRow,
        id: DownloadId?,
        options: AddOptions,
    ): LinkRow {
        // "Paused" is a pause right after the add: the engine has no add that starts paused. It runs even if the dialog
        // closes meanwhile; a small file may still finish in the moment between.
        // The download exists either way, so a pause that fails leaves it added: adding it again would make a duplicate.
        if (options.start == StartChoice.PAUSED && id != null) {
            try {
                withContext(NonCancellable) { source.pause(id) }
            } catch (_: Exception) {
                return row.copy(status = RowStatus.ADDED, message = "Added, but not paused.")
            }
        }
        return row.copy(status = RowStatus.ADDED, message = null)
    }

    private suspend fun commandOutcome(
        row: LinkRow,
        result: CommandResult,
        options: AddOptions,
    ): LinkRow =
        when (result) {
            is CommandResult.Done -> {
                added(row, result.download?.id, options)
            }

            is CommandResult.Duplicate -> {
                row.copy(
                    status = RowStatus.DUPLICATE,
                    existing = result.found.existing,
                    message = duplicateText(result.found.reason),
                )
            }

            is CommandResult.Invalid -> {
                row.copy(status = RowStatus.ADD_FAILED, message = result.reason)
            }

            else -> {
                row.copy(status = RowStatus.ADD_FAILED, message = "It could not be added.")
            }
        }

    private suspend fun torrentOutcome(
        row: LinkRow,
        result: AddTorrentResult,
        options: AddOptions,
    ): LinkRow =
        when (result) {
            is AddTorrentResult.Added -> {
                added(row, result.download.id, options)
            }

            AddTorrentResult.BadSource -> {
                row.copy(status = RowStatus.ADD_FAILED, message = "Not a torrent this app can read.")
            }

            AddTorrentResult.EmptySelection -> {
                row.copy(status = RowStatus.ADD_FAILED, message = "Choose at least one file.")
            }

            is AddTorrentResult.Failed -> {
                row.copy(
                    status = RowStatus.ADD_FAILED,
                    message =
                        result.failure.message ?: "It could not be read.",
                )
            }

            is AddTorrentResult.Refused -> {
                commandOutcome(row, result.result, options)
            }

            AddTorrentResult.Unavailable -> {
                row.copy(status = RowStatus.ADD_FAILED, message = "Torrents aren't available on this computer.")
            }
        }

    private suspend fun playlistOutcome(
        row: LinkRow,
        result: AddPlaylistResult,
        options: AddOptions,
    ): LinkRow =
        when (result) {
            is AddPlaylistResult.Added -> {
                added(row, result.collection.id, options)
            }

            is AddPlaylistResult.AllDuplicates -> {
                row.copy(status = RowStatus.ADD_FAILED, message = "Every item is already here.")
            }

            AddPlaylistResult.NotAPlaylist -> {
                row.copy(status = RowStatus.ADD_FAILED, message = "This page isn't a playlist or channel.")
            }

            AddPlaylistResult.EmptySelection -> {
                row.copy(status = RowStatus.ADD_FAILED, message = "The range and limit leave no items.")
            }

            is AddPlaylistResult.Failed -> {
                row.copy(
                    status = RowStatus.ADD_FAILED,
                    message =
                        result.failure.message ?: "It could not be read.",
                )
            }

            AddPlaylistResult.Unavailable -> {
                row.copy(status = RowStatus.ADD_FAILED, message = "Media isn't available on this computer.")
            }
        }

    // Rows from the text and options as they are now, with what the reads and adds found; starts the reads still missing.
    private fun rebuild() {
        val toProbe = mutableListOf<String>()
        val toInspect = mutableListOf<String>()
        synchronized(lock) {
            val current = ui.value
            val options = current.options
            val wants = mutableSetOf<String>()
            val rows =
                parseInput(current.text).map { line ->
                    val outcome = outcomes[line.text]
                    if (outcome?.status == RowStatus.ADDED) return@map outcome
                    val fresh = row(line, options, toProbe, toInspect, wants)
                    // A refused add shows as refused until the next one, on the row as the options make it now (a retry
                    // after switching File and Media goes the new way).
                    if (outcome != null && fresh.status in Retryable) {
                        fresh.copy(status = outcome.status, message = outcome.message, existing = outcome.existing ?: fresh.existing)
                    } else {
                        fresh
                    }
                }
            wanted = wants
            // A torrent that was read keeps its file list through a refused add, so a retry adds the same files.
            val torrentRows = rows.filter { it.kind == DownloadKind.TORRENT && inspected[it.link] is InspectResult.Inspected }
            val pick =
                torrentRows.singleOrNull()?.link?.let { torrentLink ->
                    val read = inspected[torrentLink] as? InspectResult.Inspected ?: return@let null
                    picks.getOrPut(torrentLink) {
                        TorrentPick(
                            torrentLink,
                            read.info,
                            read.info.files
                                .map { it.path }
                                .toSet(),
                        )
                    }
                }
            toProbe.distinct().forEach { probed[it] = null }
            toInspect.distinct().forEach { inspected[it] = null }
            val ftpHosts = rows.filter { it.kind == DownloadKind.FTP }.mapNotNull { it.link?.let(::hostOf) }.toSet()
            val errors =
                validate(options, now(), zone) { folders.getOrPut(it) { isFolder(it) } }.toMutableMap().apply {
                    if (options.user.isNotBlank() &&
                        ftpHosts.size > 1
                    ) {
                        put(Field.LOGIN, "A login is for one server; add the others separately.")
                    }
                }
            ui.update { it.copy(rows = rows, torrent = pick, errors = errors) }
        }
        toProbe.distinct().forEach { key -> scope.launch { probe(key) } }
        toInspect.distinct().forEach { link -> scope.launch { inspect(link) } }
    }

    // A line's row; a link not read yet is added to [toProbe], a torrent to [toInspect]. Call it holding the lock.
    private fun row(
        line: InputLine,
        options: AddOptions,
        toProbe: MutableList<String>,
        toInspect: MutableList<String>,
        wants: MutableSet<String>,
    ): LinkRow {
        if (line is InputLine.Invalid) return LinkRow(line.text, null, null, RowStatus.INVALID, message = line.reason)
        val parsed = LinkIntake.parse(line.text) ?: return LinkRow(line.text, null, null, RowStatus.NOT_A_LINK, message = "Not a link.")
        val kind = if (parsed.kind == DownloadKind.DIRECT && options.mode == AddMode.MEDIA) DownloadKind.SITE else parsed.kind
        val base = LinkRow(line.text, parsed.link, kind, RowStatus.CHECKING, addAnyway = line.text in anyway)
        if (kind !in source.available) return base.copy(status = RowStatus.UNAVAILABLE, message = "Not available on this computer.")
        // A media page is read by yt-dlp when it is added; there is nothing to read before.
        if (kind == DownloadKind.SITE) return base.copy(status = RowStatus.MEDIA, message = "Media page")
        // A torrent is read once, for its files too; a magnet can take minutes to answer.
        if (kind == DownloadKind.TORRENT) {
            wants += parsed.link
            if (parsed.link !in inspected) toInspect += parsed.link
            return when (val read = inspected[parsed.link]) {
                null -> base
                is InspectResult.Inspected -> base.copy(status = RowStatus.READY, name = read.info.name, size = read.info.total)
                is InspectResult.Failed -> base.copy(status = RowStatus.FAILED, message = read.failure.message ?: "It could not be read.")
                InspectResult.Unavailable -> base.copy(status = RowStatus.UNAVAILABLE, message = "Not available on this computer.")
            }
        }
        // A login from the fields goes with the add only, never with a read: a read per letter typed would be a failed
        // sign-in per letter, and a key file is saved only when the job is added. With one, the server isn't read first.
        // (A login typed into the link itself is read with it; that is the user's choice.)
        if (kind == DownloadKind.FTP && (options.user.isNotBlank() || options.keyPath.isNotBlank())) {
            return base.copy(status = RowStatus.READY, message = "Signs in when added")
        }
        val key = parsed.link
        wants += key
        if (key !in probed) toProbe += key
        return when (val result = probed[key]) {
            null -> {
                base
            }

            is LinkProbeResult.Probed -> {
                val named = base.copy(name = result.name, size = result.size)
                if (result.duplicate) {
                    named.copy(
                        status = RowStatus.DUPLICATE,
                        existing = result.found?.existing,
                        message = duplicateText(result.found?.reason),
                    )
                } else {
                    named.copy(status = RowStatus.READY)
                }
            }

            LinkProbeResult.NotALink -> {
                base.copy(status = RowStatus.NOT_A_LINK, message = "Not a link.")
            }

            is LinkProbeResult.Failed -> {
                base.copy(status = RowStatus.FAILED, message = result.failure.message ?: "It could not be read.")
            }

            LinkProbeResult.Unavailable -> {
                base.copy(status = RowStatus.UNAVAILABLE, message = "Not available on this computer.")
            }
        }
    }

    // True when [key] is still wanted after the settle time; otherwise it is forgotten, to be asked for again if it comes back.
    private suspend fun settled(
        key: String,
        cache: MutableMap<String, *>,
    ): Boolean {
        if (settle.isPositive()) delay(settle)
        synchronized(lock) {
            if (key in wanted) return true
            cache.remove(key)
            return false
        }
    }

    private suspend fun probe(key: String) {
        if (!settled(key, probed)) return
        val result =
            probes.withPermit {
                try {
                    source.probeLink(key)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    LinkProbeResult.Unavailable
                }
            }
        synchronized(lock) { probed[key] = result }
        rebuild()
    }

    private suspend fun inspect(link: String) {
        if (!settled(link, inspected)) return
        val result =
            inspects.withPermit {
                try {
                    source.inspectTorrent(link)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    InspectResult.Unavailable
                }
            }
        synchronized(lock) { inspected[link] = result }
        rebuild()
    }
}

private fun duplicateText(reason: DuplicateReason?): String =
    when (reason) {
        DuplicateReason.SAME_URL -> "Already in the list."
        DuplicateReason.SAME_FILE -> "The same file is already in the list."
        DuplicateReason.IN_HISTORY -> "Downloaded before (in the history)."
        DuplicateReason.ON_DISK -> "Already on disk."
        null -> "Already here."
    }
