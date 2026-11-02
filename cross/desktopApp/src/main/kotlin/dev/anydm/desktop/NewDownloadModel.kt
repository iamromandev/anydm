package dev.anydm.desktop

import dev.anydm.engine.AddTorrentResult
import dev.anydm.engine.InspectResult
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.net.BondedNetwork
import dev.anydm.engine.net.NetworkInfo
import dev.anydm.engine.os.LinkIntake
import dev.anydm.engine.os.LinkProbeResult
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.torrent.TorrentInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** What is known about the link in the field. */
sealed interface LinkState {
    data object Idle : LinkState

    data object Checking : LinkState

    /** The link was read: [name] and [size] (null when the server doesn't say), and for a torrent its [torrent] files. */
    data class Ready(
        val link: String,
        val kind: DownloadKind,
        val name: String,
        val size: Long?,
        /** The server takes ranges, so the file can be fetched in parts. */
        val resumable: Boolean,
        /** Adding it now would be refused as a duplicate. */
        val duplicate: Boolean,
        val torrent: TorrentInfo? = null,
    ) : LinkState

    data class Failed(
        val message: String,
    ) : LinkState
}

/** One file of a torrent in the tree: [shown] is its path inside the torrent's folder. */
data class TorrentFileRow(
    val index: Int,
    val path: String,
    val shown: String,
    val size: Long,
    val selected: Boolean,
)

/** A network as the dialog shows it: whether this download would use it ([on]), by the engine's own switch. */
data class NetworkChip(
    val key: String,
    val name: String,
    val colorId: String,
    val kind: NetworkKind,
    val on: Boolean,
)

data class NewDownloadState(
    val url: String = "",
    val link: LinkState = LinkState.Idle,
    /** The file's name as it will be saved: the suggested one until it is typed over. */
    val fileName: String = "",
    val files: List<TorrentFileRow> = emptyList(),
    /** The folder a multi-file torrent comes in, the row that ticks all of its files. */
    val folder: String? = null,
    val selectedCount: Int = 0,
    val selectedSize: Long? = null,
    val saveFolder: String = "",
    val saveFolderShown: String = "",
    val chips: List<NetworkChip> = emptyList(),
    val networksNote: String? = null,
    val subnetWarning: String? = null,
    val connectionNote: String? = null,
    val duplicate: Boolean = false,
    val starting: Boolean = false,
    val error: String? = null,
    val canStart: Boolean = false,
    val startLabel: String = "Download",
    /** The download was added: the dialog can close. */
    val done: Boolean = false,
)

private data class Draft(
    val url: String = "",
    val link: LinkState = LinkState.Idle,
    val skipped: Set<Int> = emptySet(),
    val fileName: String? = null,
    val saveFolder: String = "",
    val refusedAsDuplicate: Boolean = false,
    val starting: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
)

/**
 * One New download dialog: the link in the field and what the engine makes of it (a file's name and size, or a torrent's file
 * tree), where it will be saved, the networks in use and what the user starts. A link typed in is looked at after
 * [debounceMillis] of quiet; one filled in (opened with, pasted, a picked .torrent) at once. [start] adds the download and sets
 * [NewDownloadState.done]. Which networks carry a download is the engine's switch for all downloads, so the chips here only
 * show it ([subnet] tells which subnet a network is on, for the shared-subnet warning).
 */
class NewDownloadModel(
    private val source: EngineSource,
    private val looks: StateFlow<NetworkLooks>,
    private val scope: CoroutineScope,
    initial: String = "",
    private val homeFolder: String = System.getProperty("user.home").orEmpty(),
    private val debounceMillis: Long = 600,
    private val subnet: (NetworkInfo) -> String? = { subnetOf(it) },
) {
    private val draft = MutableStateFlow(Draft())
    private val starting = AtomicBoolean(false)

    // Links begin from the UI thread and from coroutines on other threads (the clipboard): the count of the link being looked at
    // and its job change together, under [lock].
    private val lock = Any()
    private var job: Job? = null

    @Volatile
    private var generation = 0
    private var subnets = Subnets(emptyList())

    val state: StateFlow<NewDownloadState> =
        combine(draft, source.networks, looks) { draft, networks, looks -> build(draft, networks.networks, looks) }
            .stateIn(scope, SharingStarted.Eagerly, build(Draft(), source.networks.value.networks, looks.value))

    init {
        scope.launch {
            val folder =
                try {
                    source.transferSettings().defaultSaveFolder
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                } ?: java.nio.file.Path
                    .of(homeFolder, "Downloads")
                    .toString()
            draft.update { if (it.saveFolder.isEmpty()) it.copy(saveFolder = folder) else it }
        }
        if (initial.isNotBlank()) {
            fill(initial)
        } else {
            // A link on the clipboard is most likely what the dialog was opened for.
            scope.launch {
                val link = clipboardLink(source.clipboard)
                if (link != null) prefill(link)
            }
        }
    }

    /** The field changed: the link is looked at once typing pauses. */
    fun setUrl(text: String) = begin(text, delay = true)

    /** A link that arrived whole (clipboard, a picked file): looked at at once. */
    fun fill(text: String) = begin(text, delay = false)

    fun clear() = begin("", delay = false)

    // The clipboard's link goes in only if nothing has been typed or filled in since the dialog opened.
    private fun prefill(link: String) {
        synchronized(lock) {
            if (generation == 0 && draft.value.url.isBlank()) begin(link, delay = false)
        }
    }

    private fun begin(
        text: String,
        delay: Boolean,
    ) {
        synchronized(lock) { beginLocked(text, delay) }
    }

    private fun beginLocked(
        text: String,
        delay: Boolean,
    ) {
        job?.cancel()
        val mine = ++generation
        draft.update {
            it.copy(
                url = text,
                link = if (text.isBlank()) LinkState.Idle else LinkState.Checking,
                skipped = emptySet(),
                fileName = null,
                refusedAsDuplicate = false,
                error = null,
            )
        }
        job =
            if (text.isBlank()) {
                null
            } else {
                scope.launch {
                    if (delay) delay(debounceMillis)
                    val answer = resolve(text.trim())
                    // Checked where it is written: an answer for a link that has been replaced since is dropped.
                    draft.update { if (mine == generation) it.copy(link = answer) else it }
                }
            }
    }

    private suspend fun resolve(text: String): LinkState {
        if (text.length > MAX_LINK_CHARS) return LinkState.Failed("That link is too long for anydm to download.")
        val parsed =
            LinkIntake.parse(text)
                ?: return LinkState.Failed(
                    "That is not a link anydm can download. Paste a web, FTP or magnet link, or open a .torrent file.",
                )
        return try {
            if (parsed.kind == DownloadKind.TORRENT) {
                when (val inspected = source.inspectTorrent(parsed.link)) {
                    is InspectResult.Inspected -> {
                        val info = inspected.info
                        LinkState.Ready(
                            parsed.link,
                            parsed.kind,
                            info.name,
                            info.total,
                            resumable = true,
                            duplicate = false,
                            torrent = info,
                        )
                    }

                    is InspectResult.Failed -> {
                        LinkState.Failed(inspected.failure.message)
                    }

                    InspectResult.Unavailable -> {
                        LinkState.Failed("Torrents are not available in this install.")
                    }
                }
            } else {
                when (val probed = source.probeLink(text)) {
                    is LinkProbeResult.Probed -> {
                        LinkState.Ready(
                            // The link as typed: the engine's own copy has any login taken out.
                            parsed.link,
                            probed.kind,
                            probed.name ?: parsed.link
                                .substringBefore('?')
                                .substringAfterLast('/')
                                .ifEmpty { "download" },
                            probed.size,
                            probed.rangesSupported,
                            probed.duplicate,
                        )
                    }

                    LinkProbeResult.NotALink -> {
                        LinkState.Failed("That is not a link anydm can download.")
                    }

                    is LinkProbeResult.Failed -> {
                        LinkState.Failed(probed.failure.message)
                    }

                    LinkProbeResult.Unavailable -> {
                        LinkState.Failed("This kind of link can't be checked here.")
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IllegalStateException) {
            LinkState.Failed("The engine has stopped.")
        } catch (e: Exception) {
            LinkState.Failed("The link could not be checked: ${e.message ?: e::class.simpleName}")
        }
    }

    fun toggleFile(index: Int) = draft.update { it.copy(skipped = if (index in it.skipped) it.skipped - index else it.skipped + index) }

    /** The folder's box: everything ticked (when anything is not) or everything cleared. */
    fun toggleAllFiles() {
        val torrent = (draft.value.link as? LinkState.Ready)?.torrent ?: return
        draft.update { it.copy(skipped = if (it.skipped.isEmpty()) torrent.files.indices.toSet() else emptySet()) }
    }

    fun setFileName(name: String) = draft.update { it.copy(fileName = name) }

    fun setSaveFolder(path: String) = draft.update { it.copy(saveFolder = path) }

    /** Adds the download; the dialog is [NewDownloadState.done] when the engine took it. */
    fun start() {
        // One reading of the draft is all that is judged and all that is added, so an edit meanwhile can't split the two.
        var snapshot: Draft
        var ready: LinkState.Ready
        var now: NewDownloadState
        do {
            snapshot = draft.value
            ready = snapshot.link as? LinkState.Ready ?: return
            now = build(snapshot, source.networks.value.networks, looks.value)
            if (!now.canStart || !starting.compareAndSet(false, true)) return
            if (draft.compareAndSet(snapshot, snapshot.copy(starting = true, error = null))) break
            // The draft moved on while this was read: look again.
            starting.set(false)
        } while (true)
        scope.launch {
            var outcome: Draft.() -> Draft
            try {
                outcome = add(now, ready)
            } catch (e: CancellationException) {
                throw e
            } catch (_: IllegalStateException) {
                outcome = { copy(error = "The engine has stopped.") }
            } catch (e: Exception) {
                outcome = { copy(error = "It could not be added: ${e.message ?: e::class.simpleName}") }
            } finally {
                starting.set(false)
            }
            draft.update { outcome(it.copy(starting = false)) }
        }
    }

    private suspend fun add(
        now: NewDownloadState,
        ready: LinkState.Ready,
    ): Draft.() -> Draft {
        val torrent = ready.torrent
        if (torrent != null) {
            val chosen = now.files.filter { it.selected }
            val template = NewDownload(DownloadKind.TORRENT, saveFolder = now.saveFolder, allowDuplicate = now.duplicate)
            val files = if (chosen.size == now.files.size) null else chosen.map { it.path }.toSet()
            return when (val result = source.addTorrent(ready.link, files, false, template, torrent)) {
                is AddTorrentResult.Added -> {
                    { copy(done = true) }
                }

                AddTorrentResult.BadSource -> {
                    { copy(error = "That is not a torrent address anydm can read.") }
                }

                AddTorrentResult.EmptySelection -> {
                    { copy(error = "Tick at least one file.") }
                }

                is AddTorrentResult.Failed -> {
                    { copy(error = result.failure.message) }
                }

                is AddTorrentResult.Refused -> {
                    refused(result.result)
                }

                AddTorrentResult.Unavailable -> {
                    { copy(error = "Torrents are not available in this install.") }
                }
            }
        }
        val named = now.fileName.trim().takeIf { it.isNotEmpty() && it != ready.name }
        val new =
            NewDownload(
                ready.kind,
                mirrors = listOf(ready.link),
                saveFolder = now.saveFolder,
                fileName = named,
                size = ready.size,
                allowDuplicate = now.duplicate,
            )
        return when (val result = source.add(new)) {
            is CommandResult.Done -> {
                { copy(done = true) }
            }

            else -> {
                refused(result)
            }
        }
    }

    private fun refused(result: CommandResult): Draft.() -> Draft =
        when (result) {
            is CommandResult.Done -> {
                { copy(done = true) }
            }

            is CommandResult.Duplicate -> {
                { copy(refusedAsDuplicate = true, error = "This is already in your list or was downloaded before.") }
            }

            is CommandResult.Invalid -> {
                { copy(error = result.reason) }
            }

            else -> {
                { copy(error = "The download could not be added.") }
            }
        }

    private fun build(
        draft: Draft,
        networks: List<BondedNetwork>,
        looks: NetworkLooks,
    ): NewDownloadState {
        val ready = draft.link as? LinkState.Ready
        val torrent = ready?.torrent
        val shownFolder = torrent?.files?.let(::folderOf)
        val files =
            torrent?.files.orEmpty().map { file ->
                TorrentFileRow(
                    file.index,
                    file.path,
                    if (shownFolder != null) file.path.removePrefix("$shownFolder/") else file.path,
                    file.size.coerceAtLeast(0),
                    file.index !in draft.skipped,
                )
            }
        val chosen = files.filter { it.selected }
        val duplicate = (ready?.duplicate ?: false) || draft.refusedAsDuplicate
        val chips =
            networks.sortedBy { it.info.key }.map { net ->
                NetworkChip(
                    net.info.key,
                    looks.nameOf(net.info.key, net.info.name),
                    looks.colorIds[net.info.key] ?: SWATCH_IDS.first(),
                    kindOf(net.info),
                    net.active,
                )
            }
        val sorted = networks.sortedBy { it.info.key }
        val inUse = sorted.filter { it.active }
        val saveFolder = draft.saveFolder
        val canStart =
            ready != null && !draft.starting && saveFolder.isNotEmpty() && (torrent == null || chosen.isNotEmpty()) && !draft.done
        return NewDownloadState(
            url = draft.url,
            link = draft.link,
            fileName = draft.fileName ?: ready?.name.orEmpty(),
            files = files,
            folder = shownFolder,
            selectedCount = chosen.size,
            selectedSize = if (torrent != null) chosen.sumOf { it.size } else ready?.size,
            saveFolder = saveFolder,
            saveFolderShown = toDisplayPath(saveFolder, homeFolder),
            chips = chips,
            networksNote = if (chips.isEmpty()) null else "Networks are switched for all downloads in the Networks menu.",
            subnetWarning = subnetWarning(networks, inUse, looks),
            connectionNote =
                when {
                    ready == null || torrent != null -> null
                    !ready.resumable -> "Uses one connection. This server doesn't support parallel downloads."
                    ready.size == null -> "Uses one connection. The file size is unknown."
                    else -> null
                },
            duplicate = duplicate,
            starting = draft.starting,
            error = draft.error,
            canStart = canStart,
            startLabel =
                when {
                    draft.starting -> "Starting"
                    draft.link == LinkState.Checking -> "Checking"
                    duplicate -> "Download anyway"
                    else -> "Download"
                },
            done = draft.done,
        )
    }

    // Two networks on one subnet: the computer sends both down one route, so there is nothing to combine.
    private fun subnetWarning(
        listing: List<BondedNetwork>,
        inUse: List<BondedNetwork>,
        looks: NetworkLooks,
    ): String? {
        if (inUse.size < 2) return null
        val bySubnet =
            inUse
                .mapNotNull { net ->
                    subnetIn(listing, net.info)?.let { it to looks.nameOf(net.info.key, net.info.name) }
                }.groupBy({ it.first }, { it.second })
        val (where, names) = bySubnet.entries.firstOrNull { it.value.size > 1 } ?: return null
        val list = if (names.size == 2) names.joinToString(" and ") else names.dropLast(1).joinToString(", ") + " and " + names.last()
        return "$list share a subnet ($where), so the computer sends both down one route and they can't be combined."
    }

    // The subnets are worked out once for each listing the engine gives, not again for every key typed.
    private class Subnets(
        val listing: List<BondedNetwork>,
    ) {
        /** A network's subnet by key; the value is null when it has none, a missing key when it was not worked out yet. */
        val byKey = HashMap<String, String?>()
    }

    private fun subnetIn(
        listing: List<BondedNetwork>,
        info: NetworkInfo,
    ): String? {
        synchronized(lock) {
            if (subnets.listing !== listing) subnets = Subnets(listing)
            if (info.key in subnets.byKey) return subnets.byKey[info.key]
        }
        // Worked out outside the lock: it asks the OS about the interface, and link changes wait on the lock.
        val found = subnet(info)
        synchronized(lock) {
            if (subnets.listing === listing) subnets.byKey[info.key] = found
        }
        return found
    }

    // A torrent that comes in a folder starts every path with it (always so with several files).
    private fun folderOf(files: List<dev.anydm.engine.torrent.TorrentFile>): String? {
        val first =
            files
                .firstOrNull()
                ?.path
                ?.takeIf { '/' in it }
                ?.substringBefore('/') ?: return null
        return first.takeIf { folder -> files.all { it.path.startsWith("$folder/") } }
    }
}
