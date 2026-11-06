package dev.anydm.desktop

import dev.anydm.engine.AddTorrentResult
import dev.anydm.engine.DownloadSnapshot
import dev.anydm.engine.EngineEvent
import dev.anydm.engine.EngineState
import dev.anydm.engine.FileSnapshot
import dev.anydm.engine.InspectResult
import dev.anydm.engine.SpeedWindow
import dev.anydm.engine.TorrentDetailResult
import dev.anydm.engine.core.Attempt
import dev.anydm.engine.core.CategoryId
import dev.anydm.engine.core.CollectionKind
import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.ItemCounts
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.core.PauseReason
import dev.anydm.engine.core.ScheduleRuleId
import dev.anydm.engine.net.AllowanceStatus
import dev.anydm.engine.net.BondingState
import dev.anydm.engine.net.NetworkPolicy
import dev.anydm.engine.net.NetworkRules
import dev.anydm.engine.net.NewScheduleRule
import dev.anydm.engine.net.ScheduleRule
import dev.anydm.engine.os.BridgePairing
import dev.anydm.engine.os.LinkIntake
import dev.anydm.engine.os.LinkProbeResult
import dev.anydm.engine.os.PairedBrowser
import dev.anydm.engine.os.PairedBrowsers
import dev.anydm.engine.os.PairingState
import dev.anydm.engine.os.RevokeResult
import dev.anydm.engine.platform.Clipboard
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.queue.RemoveFiles
import dev.anydm.engine.store.DayTraffic
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.TransferSettings
import dev.anydm.engine.torrent.TorrentInfo
import dev.anydm.engine.torrent.TorrentSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * An [EngineSource] the tests drive by hand. [calls] records each command as text ("pause 3", "remove 2 KEEP"); [results]
 * answers one by that text, [Done] otherwise; [refuseAll] makes every command throw as a stopped engine does.
 */
internal class FakeSource(
    var startError: Throwable? = null,
) : EngineSource {
    override val state = MutableStateFlow<EngineState>(EngineState.NotStarted)
    override val downloads = MutableStateFlow<List<DownloadSnapshot>>(emptyList())
    override val totalSpeed = MutableStateFlow(SpeedWindow.Empty)
    override val events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64)
    override var available: Set<DownloadKind> = setOf(DownloadKind.DIRECT, DownloadKind.SITE, DownloadKind.TORRENT, DownloadKind.FTP)
    override val networks = MutableStateFlow(BondingState(emptyList()))
    var clipboardText: String? = null

    /** Counted down once the clipboard has been read: a test waits on it before asserting that a read changed nothing. */
    val clipboardRead = java.util.concurrent.CountDownLatch(1)
    override val clipboard =
        Clipboard {
            clipboardText.also { clipboardRead.countDown() }
        }

    /** What [freeSpace] answers per folder; null for a folder with no entry. */
    val free = mutableMapOf<String, Long>()

    override fun freeSpace(folder: String): Long? = free[folder]

    var started = 0
    var stopped = 0
    val calls = mutableListOf<String>()

    /** The commands that ran to their answer (past the [gate]). */
    val finished = mutableListOf<String>()
    val results = mutableMapOf<String, CommandResult>()
    var refuseAll = false
    var torrent: TorrentDetailResult = TorrentDetailResult.NotInSession

    /** What [torrentDetail] answers for a job that has its own entry, instead of [torrent]. */
    val torrentById = mutableMapOf<Long, TorrentDetailResult>()

    /** Thrown by the plain commands (pause, resume, ...) once set. */
    var commandThrows: Throwable? = null

    /** Thrown by [torrentDetail] once set: a poll that fails. */
    var detailThrows: Throwable? = null

    /** While set, every command waits for it after it is recorded: a command still in flight. */
    var gate: CompletableDeferred<Unit>? = null

    /** Holds the command with this text until completed, whatever the [gate]. */
    val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

    /** While set, [start] waits for it: an engine still starting. */
    var startGate: CompletableDeferred<Unit>? = null

    override suspend fun start() {
        started++
        startGate?.await()
        startError?.let { throw it }
        state.value = EngineState.Running
    }

    override suspend fun stop() {
        stopped++
        state.value = EngineState.Stopped
    }

    private suspend fun call(text: String): CommandResult {
        if (refuseAll) throw IllegalStateException("the engine has stopped")
        commandThrows?.let { throw it }
        synchronized(calls) { calls += text }
        gate?.await()
        gates[text]?.await()
        synchronized(calls) { finished += text }
        return results[text] ?: CommandResult.Done(null)
    }

    override suspend fun pause(id: DownloadId) = call("pause ${id.value}")

    override suspend fun resume(id: DownloadId) = call("resume ${id.value}")

    override suspend fun cancel(id: DownloadId) = call("cancel ${id.value}")

    override suspend fun remove(
        id: DownloadId,
        files: RemoveFiles,
    ) = call("remove ${id.value} $files")

    override suspend fun retry(
        id: DownloadId,
        allowDuplicate: Boolean,
    ) = call(if (allowDuplicate) "retry ${id.value} anyway" else "retry ${id.value}")

    override suspend fun reprioritize(
        id: DownloadId,
        priority: Int,
    ): CommandResult {
        val done = call("reprioritize ${id.value} $priority")
        if (done is CommandResult.Done) {
            downloads.value =
                downloads.value.map {
                    if (it.download.id == id) it.copy(download = it.download.copy(priority = priority)) else it
                }
        }
        return done
    }

    override suspend fun relink(
        id: DownloadId,
        mirrorId: MirrorId,
        url: String,
    ) = call("relink ${id.value} ${mirrorId.value} $url")

    override suspend fun pauseAll() = call("pauseAll")

    override suspend fun resumeAll() = call("resumeAll")

    override suspend fun torrentDetail(id: DownloadId): TorrentDetailResult {
        call("torrentDetail ${id.value}")
        detailThrows?.let { throw it }
        return torrentById[id.value] ?: torrent
    }

    override suspend fun setTorrentFiles(
        id: DownloadId,
        priorities: List<Int>,
    ) = call("torrentFiles ${id.value} $priorities")

    /** What [probeLink] answers per link; any other web link probes as a new file named after its last segment. */
    val probes = mutableMapOf<String, LinkProbeResult>()
    val probed = mutableListOf<String>()
    val inspects = mutableMapOf<String, InspectResult>()
    val addResults = mutableMapOf<String, CommandResult>()
    var torrentResult: AddTorrentResult? = null

    /** Every add as the engine got it. */
    val added = mutableListOf<NewDownload>()
    val torrentsAdded = mutableListOf<TorrentAdd>()

    data class TorrentAdd(
        val source: String,
        val files: Set<String>?,
        val sequential: Boolean,
        val template: NewDownload,
        val info: TorrentInfo? = null,
    )

    /** [probeLink] goes on waiting for the [gate] when its caller is cancelled, as an engine call that can't be interrupted does. */
    var probeIgnoresCancel = false

    /** Thrown by every add, as a store error would be. */
    var addThrows: Exception? = null

    override suspend fun probeLink(raw: String): LinkProbeResult {
        if (refuseAll) throw IllegalStateException("the engine has stopped")
        synchronized(probed) { probed += raw }
        if (probeIgnoresCancel) withContext(NonCancellable) { gate?.await() } else gate?.await()
        return probes[raw]
            ?: LinkIntake.parse(raw)?.let { LinkProbeResult.Probed(it.link, it.kind, it.link.substringAfterLast('/'), 1000, true, false) }
            ?: LinkProbeResult.NotALink
    }

    override suspend fun inspectTorrent(source: String): InspectResult = inspects[source] ?: InspectResult.Unavailable

    override suspend fun add(new: NewDownload): CommandResult {
        call("add ${new.mirrors.first()}")
        addThrows?.let { throw it }
        added += new
        return addResults[new.mirrors.first()] ?: CommandResult.Done(Download(DownloadId(100L + added.size), new.kind))
    }

    override suspend fun addTorrent(
        source: String,
        files: Set<String>?,
        sequential: Boolean,
        template: NewDownload,
        info: TorrentInfo?,
    ): AddTorrentResult {
        call("addTorrent $source")
        torrentsAdded += TorrentAdd(source, files, sequential, template, info)
        return torrentResult ?: AddTorrentResult.Added(Download(DownloadId(200), DownloadKind.TORRENT))
    }

    // Pairing, scripted: the codes handed out in turn, what the newest one is in, and the browsers paired.

    /** The codes [startPairing] hands out in turn (then [DEFAULT_CODE]). */
    val pairCodes = ArrayDeque<String>()
    var pairLife: Duration = BridgePairing.CODE_LIFE

    /** What "now" is when [startPairing] works out when a code ends; a test points it at its fake clock. */
    var pairNow: () -> Instant = { Clock.System.now() }

    /** What the newest code is in; every earlier one is EXPIRED, as the engine replaces a code with the next. */
    var pairState: PairingState = PairingState.WAITING

    /** Thrown by [startPairing] while set. */
    var pairingThrows: Exception? = null

    /** Run at the start of each code's `state()` (a test makes something happen at that moment). */
    @Volatile
    var onState: () -> Unit = {}

    /** The codes a cancel named, in order; null for a cancel that named none. */
    val cancelledWith = java.util.Collections.synchronizedList(mutableListOf<PairingCode?>())

    /** Thrown by a code's `state()` while set. */
    var pairStateThrows: Exception? = null

    /** While set, [startPairing] waits for it. */
    var pairGate: CompletableDeferred<Unit>? = null

    /** Makes the wait on [pairGate] immune to cancellation: the engine's own work, once begun, runs to its end. */
    var pairGateHolds = false

    /** While set, [revokeBrowser] waits for it before it changes anything. */
    var revokeGate: CompletableDeferred<Unit>? = null
    var pairedList: PairedBrowsers = PairedBrowsers.Listed(emptyList())
    var pairedThrows: Exception? = null
    var revokeResult: RevokeResult = RevokeResult.REVOKED

    /** Held up by the browser list: [pairedBrowsers] waits for it once set. */
    var pairedGate: CompletableDeferred<Unit>? = null

    /** The names of the threads the pairing calls ran on. */
    val pairThreads = java.util.Collections.synchronizedList(mutableListOf<String>())
    val pairCodesIssued = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val stateAsks =
        java.util.concurrent.atomic
            .AtomicInteger()

    /** The code [startPairing] handed out last. */
    @Volatile
    var lastIssued: PairingCode? = null

    /** Counted down the first time a code's state is answered: a test on another thread waits on it. */
    val stateSeen = java.util.concurrent.CountDownLatch(1)

    // The codes issued so far are all ended by a cancel (a later one is a new code).
    private val cancelledUpTo =
        java.util.concurrent.atomic
            .AtomicInteger()

    /** How many times a code's state was asked for. */
    val stateAsked: Int get() = stateAsks.get()

    override suspend fun startPairing(): PairingCode {
        synchronized(calls) { calls += "startPairing" }
        pairThreads += Thread.currentThread().name
        pairingThrows?.let { throw it }
        pairGate?.let { gate -> if (pairGateHolds) withContext(NonCancellable) { gate.await() } else gate.await() }
        val grouped = pairCodes.removeFirstOrNull() ?: DEFAULT_CODE
        val number = synchronized(pairCodesIssued) { pairCodesIssued.also { it += grouped }.size }
        return PairingCode(grouped, pairNow() + pairLife) {
            onState()
            stateAsks.incrementAndGet()
            pairThreads += Thread.currentThread().name
            stateSeen.countDown()
            pairStateThrows?.let { throw it }
            if (pairCodesIssued.size == number && number > cancelledUpTo.get()) pairState else PairingState.EXPIRED
        }.also { lastIssued = it }
    }

    override suspend fun cancelPairing(only: PairingCode?) {
        synchronized(calls) { calls += "cancelPairing" }
        pairThreads += Thread.currentThread().name
        cancelledWith += only
        // As the engine: a code named is ended only while it is the newest.
        if (only != null && only !== lastIssued) return
        cancelledUpTo.set(pairCodesIssued.size)
    }

    override suspend fun pairedBrowsers(): PairedBrowsers {
        synchronized(calls) { calls += "pairedBrowsers" }
        pairThreads += Thread.currentThread().name
        pairedThrows?.let { throw it }
        pairedGate?.await()
        return pairedList
    }

    override suspend fun revokeBrowser(id: String): RevokeResult {
        synchronized(calls) { calls += "revokeBrowser $id" }
        pairThreads += Thread.currentThread().name
        revokeGate?.await()
        val list = pairedList
        if (revokeResult == RevokeResult.REVOKED && list is PairedBrowsers.Listed) {
            pairedList = PairedBrowsers.Listed(list.browsers.filter { it.id != id })
        }
        return revokeResult
    }

    override val transferLock = Mutex()

    // Transfer settings, kept in memory as the engine would keep them.
    var transfer = TransferSettings()

    /** Answers apply and reset instead of Done, keeping what was there. */
    var settingsResult: CommandResult? = null

    override suspend fun transferSettings(): TransferSettings {
        if (refuseAll) throw IllegalStateException("the engine has stopped")
        return transfer
    }

    override suspend fun applyTransferSettings(next: TransferSettings): CommandResult {
        call("applyTransferSettings")
        settingsResult?.let { return it }
        next.problems().firstOrNull()?.let { return CommandResult.Invalid(it) }
        transfer = next
        return CommandResult.Done(null)
    }

    override suspend fun resetTransferSettings(): CommandResult {
        call("resetTransferSettings")
        settingsResult?.let { return it }
        transfer = TransferSettings(globalPause = transfer.globalPause, afterQueue = transfer.afterQueue)
        return CommandResult.Done(null)
    }

    var rules = NetworkRules()
    var scheduleList: List<ScheduleRule> = emptyList()
    private var nextRule = 1L

    override suspend fun networkRules(): NetworkRules = rules

    override suspend fun setNetworkPolicy(
        network: String?,
        policy: NetworkPolicy?,
    ): CommandResult {
        val done = call("setNetworkPolicy ${network ?: "*"}")
        if (done !is CommandResult.Done) return done
        rules =
            when {
                network == null -> rules.copy(default = policy ?: NetworkPolicy())
                policy == null -> rules.copy(networks = rules.networks - network)
                else -> rules.copy(networks = rules.networks + (network to policy))
            }
        return done
    }

    /** What [allowanceUsage] returns; a test sets it. Not recorded as a call: the UI asks every few seconds. */
    var usage: Map<String, AllowanceStatus> = emptyMap()

    /** Thrown by [allowanceUsage] while set. */
    var usageThrows: Exception? = null

    override suspend fun allowanceUsage(): Map<String, AllowanceStatus> {
        usageThrows?.let { throw it }
        return usage
    }

    /** What [trafficByDay] returns (every day, whatever the range); a test sets it. */
    var traffic: List<DayTraffic> = emptyList()

    override suspend fun trafficByDay(
        from: String,
        to: String,
    ): List<DayTraffic> {
        if (refuseAll) throw IllegalStateException("the engine has stopped")
        return traffic.filter { it.day in from..to }
    }

    override suspend fun resetAllowanceUsage(network: String): CommandResult {
        val done = call("resetAllowanceUsage $network")
        if (done !is CommandResult.Done) return done
        // As the engine: a network with no allowance has no usage to start over.
        if (rules.policyFor(network).allowance == null) return CommandResult.Invalid("network has no data limit: $network")
        usage = usage.mapValues { (key, it) -> if (key == network) it.copy(used = 0, over = false) else it }
        return done
    }

    override suspend fun scheduleRules(): List<ScheduleRule> = scheduleList

    /** The number of [addScheduleRule] calls (1 is the first) that throw `IOException("disk full")`; null for none. */
    var addRuleFailsAt: Int? = null
    private var addRuleCalls = 0

    /** The number of the [addScheduleRule] call that stores its rule and then throws (an answer lost on the way back); null for none. */
    var addRuleFailsAfterStoringAt: Int? = null

    /** Ids of rules whose [removeScheduleRule] throws `IOException("disk locked")` and leaves the rule in place. */
    var removeRuleFails: Set<Long> = emptySet()

    override suspend fun addScheduleRule(new: NewScheduleRule): ScheduleRule {
        call("addScheduleRule")
        val number = ++addRuleCalls
        if (number == addRuleFailsAt) throw java.io.IOException("disk full")
        return ScheduleRule(ScheduleRuleId(nextRule++), new.days, new.startMinute, new.endMinute, new.action).also {
            scheduleList = scheduleList + it
            if (number == addRuleFailsAfterStoringAt) throw java.io.IOException("answer lost")
        }
    }

    override suspend fun updateScheduleRule(rule: ScheduleRule): Boolean {
        call("updateScheduleRule ${rule.id.value}")
        val there = scheduleList.any { it.id == rule.id }
        scheduleList = scheduleList.map { if (it.id == rule.id) rule else it }
        return there
    }

    override suspend fun removeScheduleRule(id: ScheduleRuleId): Boolean {
        call("removeScheduleRule ${id.value}")
        if (id.value in removeRuleFails) throw java.io.IOException("disk locked")
        val there = scheduleList.any { it.id == id }
        scheduleList = scheduleList.filter { it.id != id }
        return there
    }

    override suspend fun setNetworkEnabled(
        key: String,
        enabled: Boolean,
    ): CommandResult {
        val done = call("setNetworkEnabled $key $enabled")
        if (done is CommandResult.Done) {
            networks.value =
                BondingState(networks.value.networks.map { if (it.info.key == key) it.copy(enabled = enabled) else it })
        }
        return done
    }
}

internal const val DEFAULT_CODE = "ABCD-EFGH-JKMN-PQRS"

internal fun browser(
    id: String,
    label: String = "Firefox on Mac",
    createdAt: Instant = Instant.fromEpochSeconds(1_000),
    lastUsed: Instant = createdAt,
) = PairedBrowser(id, label, createdAt, lastUsed)

internal fun snapshot(
    id: Long,
    status: DownloadStatus,
    category: CategoryId? = null,
    pauseReasons: Set<PauseReason> = emptySet(),
    name: String? = null,
    size: Long? = null,
    downloaded: Long = 0,
    total: Long? = size,
    speed: Long = 0,
    priority: Int = 0,
    parent: Long? = null,
    collection: CollectionKind? = null,
    playlistIndex: Int? = null,
    items: ItemCounts? = null,
    attempts: List<Attempt> = emptyList(),
    mirrors: List<Mirror> = emptyList(),
    files: List<FileSnapshot> = emptyList(),
    torrent: TorrentSummary? = null,
    networkShare: Map<String, Long> = emptyMap(),
    speedSamples: List<Long> = emptyList(),
    saveFolder: String? = null,
    kind: DownloadKind = if (torrent != null) DownloadKind.TORRENT else DownloadKind.DIRECT,
): DownloadSnapshot =
    DownloadSnapshot(
        download =
            Download(
                DownloadId(id),
                kind,
                status,
                parentId = parent?.let(::DownloadId),
                collection = collection,
                categoryId = category,
                saveFolder = saveFolder,
                fileName = name,
                size = size,
                priority = priority,
                pauseReasons = pauseReasons,
                playlistIndex = playlistIndex,
            ),
        downloaded = downloaded,
        total = total,
        speed = speed,
        speedSamples = speedSamples,
        lastFailure = attempts.lastOrNull { it.errorClass != null || it.message != null },
        attempts = attempts,
        mirrors = mirrors,
        files = files,
        items = items,
        torrent = torrent,
        networkShare = networkShare,
    )
