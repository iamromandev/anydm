package dev.anydm.desktop

import dev.anydm.engine.AddPlaylistResult
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
import dev.anydm.engine.core.ItemSelection
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.core.PauseReason
import dev.anydm.engine.core.ProfileId
import dev.anydm.engine.core.ProxyId
import dev.anydm.engine.core.ScheduleRuleId
import dev.anydm.engine.files.Category
import dev.anydm.engine.files.CategoryResult
import dev.anydm.engine.files.NewCategory
import dev.anydm.engine.files.NewProfile
import dev.anydm.engine.files.Profile
import dev.anydm.engine.files.ProfileResult
import dev.anydm.engine.ftp.SshKeyLogin
import dev.anydm.engine.net.AfterQueueState
import dev.anydm.engine.net.BondingState
import dev.anydm.engine.net.NetworkPolicy
import dev.anydm.engine.net.NetworkRules
import dev.anydm.engine.net.NewProxy
import dev.anydm.engine.net.NewScheduleRule
import dev.anydm.engine.net.PasswordChange
import dev.anydm.engine.net.ProxyChoice
import dev.anydm.engine.net.ProxyCommandResult
import dev.anydm.engine.net.ProxyEntry
import dev.anydm.engine.net.ScheduleRule
import dev.anydm.engine.os.ClipboardLink
import dev.anydm.engine.os.HotkeyStatus
import dev.anydm.engine.os.LinkIntake
import dev.anydm.engine.os.LinkProbeResult
import dev.anydm.engine.os.OsSettings
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.queue.RemoveFiles
import dev.anydm.engine.store.DayTraffic
import dev.anydm.engine.store.HistoryEntry
import dev.anydm.engine.store.ImportResult
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.ProxyRemoval
import dev.anydm.engine.store.RuntimeSettings
import dev.anydm.engine.store.TransferSettings
import dev.anydm.engine.tools.Tool
import dev.anydm.engine.tools.ToolStatus
import dev.anydm.engine.tools.ToolUpdate
import dev.anydm.engine.tools.YtDlpChannel
import dev.anydm.engine.torrent.TorrentInfo
import dev.anydm.engine.torrent.TorrentSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * An [EngineSource] the tests drive by hand. [calls] records each command as text ("pause 3", "remove 2 KEEP"); [results]
 * answers one by that text, [Done] otherwise; [refuseAll] makes every command throw as a stopped engine does.
 */
internal class FakeSource(
    var categoryList: List<Category> = emptyList(),
    var startError: Throwable? = null,
) : EngineSource {
    override val state = MutableStateFlow<EngineState>(EngineState.NotStarted)
    override val downloads = MutableStateFlow<List<DownloadSnapshot>>(emptyList())
    override val totalSpeed = MutableStateFlow(SpeedWindow.Empty)
    override val events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64)
    var started = 0
    var stopped = 0
    val calls = mutableListOf<String>()
    val results = mutableMapOf<String, CommandResult>()
    var refuseAll = false
    var torrent: TorrentDetailResult = TorrentDetailResult.NotInSession

    /** While set, every command waits for it after it is recorded: a command still in flight. */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun categories(): List<Category> = categoryList

    override suspend fun start() {
        started++
        startError?.let { throw it }
        state.value = EngineState.Running
    }

    override suspend fun stop() {
        stopped++
        state.value = EngineState.Stopped
    }

    private suspend fun call(text: String): CommandResult {
        if (refuseAll) throw IllegalStateException("the engine has stopped")
        synchronized(calls) { calls += text }
        gate?.await()
        return results[text] ?: CommandResult.Done(null)
    }

    override suspend fun pause(id: DownloadId): CommandResult {
        pauseThrows?.let { throw it }
        return call("pause ${id.value}")
    }

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
    ) = call("priority ${id.value} $priority")

    override suspend fun relink(
        id: DownloadId,
        mirrorId: MirrorId,
        url: String,
    ) = call("relink ${id.value} ${mirrorId.value} $url")

    override suspend fun stopSeeding(id: DownloadId) = call("stopSeeding ${id.value}")

    override suspend fun pauseAll() = call("pauseAll")

    override suspend fun resumeAll() = call("resumeAll")

    override suspend fun torrentDetail(id: DownloadId): TorrentDetailResult {
        call("torrentDetail ${id.value}")
        return torrent
    }

    override var available: Set<DownloadKind> = setOf(DownloadKind.DIRECT, DownloadKind.SITE, DownloadKind.TORRENT, DownloadKind.FTP)
    var profileList: List<Profile> = emptyList()

    /** What [probeLink] answers per link; any other web link probes as a new file named after its last segment. */
    val probes = mutableMapOf<String, LinkProbeResult>()
    val probed = mutableListOf<String>()
    val inspects = mutableMapOf<String, InspectResult>()
    val addResults = mutableMapOf<String, CommandResult>()
    var torrentResult: AddTorrentResult? = null
    var playlistResult: AddPlaylistResult? = null

    /** Every add as the engine got it. */
    val added = mutableListOf<NewDownload>()
    val torrentsAdded = mutableListOf<TorrentAdd>()
    val playlistsAdded = mutableListOf<Triple<String, ItemSelection, NewDownload>>()
    val keyLogins = mutableListOf<Pair<String, SshKeyLogin>>()

    data class TorrentAdd(
        val source: String,
        val files: Set<String>?,
        val sequential: Boolean,
        val template: NewDownload,
        val info: TorrentInfo? = null,
    )

    /** Thrown by every add, as a store error would be. */
    var addThrows: Exception? = null

    /** What saving a key login answers: false when it only reached memory. */
    var keySaved = true

    /** Thrown by pause, as an engine stopping right then would. */
    var pauseThrows: Exception? = null

    override suspend fun profiles(): List<Profile> = profileList

    override suspend fun probeLink(raw: String): LinkProbeResult {
        if (refuseAll) throw IllegalStateException("the engine has stopped")
        synchronized(probed) { probed += raw }
        gate?.await()
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

    override suspend fun addPlaylist(
        url: String,
        selection: ItemSelection,
        template: NewDownload,
    ): AddPlaylistResult {
        call("addPlaylist $url")
        playlistsAdded += Triple(url, selection, template)
        return playlistResult ?: AddPlaylistResult.Added(Download(DownloadId(300), DownloadKind.SITE), emptyList(), 0, 0)
    }

    override suspend fun saveKeyLogin(
        url: String,
        login: SshKeyLogin,
    ): Boolean {
        call("saveKeyLogin $url")
        keyLogins += url to login
        return keySaved
    }

    // Settings (#608), kept in memory as the engine would keep them.
    var transfer = TransferSettings()
    var defaultProfileId: ProfileId? = null
    var historyList: List<HistoryEntry> = emptyList()
    var trafficList: List<DayTraffic> = emptyList()
    var exported = "{}"
    val imported = mutableListOf<String>()
    var importResult: ImportResult = ImportResult.Applied

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

    private var nextId = 1000L

    private fun nameTaken(
        name: String,
        except: CategoryId?,
    ) = categoryList.any { it.id != except && it.name.equals(name.trim(), ignoreCase = true) }

    override suspend fun addCategory(new: NewCategory): CategoryResult {
        call("addCategory ${new.name}")
        if (nameTaken(new.name, null)) return CategoryResult.NameTaken
        val category = Category(CategoryId(nextId++), new.name, new.folder, new.template, new.rules, new.priority)
        categoryList = (categoryList + category).sortedWith(compareBy({ it.priority }, { it.id.value }))
        return CategoryResult.Saved(category)
    }

    override suspend fun updateCategory(category: Category): CategoryResult {
        call("updateCategory ${category.name}")
        if (categoryList.none { it.id == category.id }) return CategoryResult.NotFound
        if (nameTaken(category.name, category.id)) return CategoryResult.NameTaken
        categoryList =
            categoryList.map { if (it.id == category.id) category else it }.sortedWith(compareBy({ it.priority }, { it.id.value }))
        return CategoryResult.Saved(category)
    }

    override suspend fun deleteCategory(id: CategoryId) {
        call("deleteCategory ${id.value}")
        categoryList = categoryList.filter { it.id != id }
    }

    override suspend fun addProfile(new: NewProfile): ProfileResult {
        call("addProfile ${new.name}")
        if (profileList.any { it.name == new.name }) return ProfileResult.NameTaken
        val profile =
            Profile(ProfileId(nextId++), new.name, new.format, new.categoryId, new.saveFolder, new.nameTemplate, new.speedLimit, new.proxy)
        profileList = profileList + profile
        return ProfileResult.Saved(profile)
    }

    override suspend fun updateProfile(profile: Profile): ProfileResult {
        call("updateProfile ${profile.name}")
        if (profileList.none { it.id == profile.id }) return ProfileResult.NotFound
        if (profileList.any { it.id != profile.id && it.name == profile.name }) return ProfileResult.NameTaken
        profileList = profileList.map { if (it.id == profile.id) profile else it }
        return ProfileResult.Saved(profile)
    }

    override suspend fun deleteProfile(id: ProfileId) {
        call("deleteProfile ${id.value}")
        profileList = profileList.filter { it.id != id }
        if (defaultProfileId == id) defaultProfileId = null
    }

    override suspend fun defaultProfile(): ProfileId? = defaultProfileId

    override suspend fun setDefaultProfile(id: ProfileId?): Boolean {
        call("setDefaultProfile ${id?.value}")
        if (id != null && profileList.none { it.id == id }) return false
        defaultProfileId = id
        return true
    }

    override suspend fun history(limit: Int): List<HistoryEntry> = historyList.take(limit)

    override suspend fun clearHistory() {
        call("clearHistory")
        historyList = emptyList()
    }

    var trafficAsked: Pair<String, String>? = null

    override suspend fun trafficByDay(
        from: String,
        to: String,
    ): List<DayTraffic> {
        trafficAsked = from to to
        return trafficList
    }

    override suspend fun exportSettings(): String {
        call("exportSettings")
        return exported
    }

    override suspend fun importSettings(text: String): ImportResult {
        call("importSettings")
        imported += text
        return importResult
    }

    // --- network, OS and tool settings (#609)

    var proxyList: List<ProxyEntry> = emptyList()
    var proxyChosen: ProxyChoice = ProxyChoice.System
    var proxyUse: ProxyRemoval = ProxyRemoval.Removed

    /** Answers add and update instead of saving. */
    var proxyResult: ProxyCommandResult? = null
    val passwords = mutableMapOf<ProxyId, String>()
    private var nextProxy = 1L

    override suspend fun proxies(): List<ProxyEntry> = proxyList

    override suspend fun addProxy(
        new: NewProxy,
        password: String?,
    ): ProxyCommandResult {
        call("addProxy ${new.name}")
        proxyResult?.let { return it }
        if (proxyList.any { it.name == new.name }) return ProxyCommandResult.NameTaken
        val entry = ProxyEntry(ProxyId(nextProxy++), new.name, new.type, new.host, new.port, new.username, new.bypass)
        proxyList = proxyList + entry
        if (password != null) passwords[entry.id] = password
        return ProxyCommandResult.Saved(entry)
    }

    override suspend fun updateProxy(
        proxy: ProxyEntry,
        password: PasswordChange,
    ): ProxyCommandResult {
        call("updateProxy ${proxy.id.value}")
        proxyResult?.let { return it }
        if (proxyList.none { it.id == proxy.id }) return ProxyCommandResult.NotFound
        if (proxyList.any { it.name == proxy.name && it.id != proxy.id }) return ProxyCommandResult.NameTaken
        proxyList = proxyList.map { if (it.id == proxy.id) proxy else it }
        when (password) {
            PasswordChange.Keep -> Unit
            PasswordChange.Clear -> passwords.remove(proxy.id)
            is PasswordChange.Set -> passwords[proxy.id] = password.password
        }
        return ProxyCommandResult.Saved(proxy)
    }

    override suspend fun removeProxy(id: ProxyId): ProxyRemoval {
        call("removeProxy ${id.value}")
        if (proxyUse == ProxyRemoval.Removed) {
            proxyList = proxyList.filter { it.id != id }
            passwords.remove(id)
        }
        return proxyUse
    }

    override suspend fun proxyChoice(): ProxyChoice = proxyChosen

    override suspend fun setProxyChoice(choice: ProxyChoice): CommandResult {
        val done = call("setProxyChoice $choice")
        if (done is CommandResult.Done) proxyChosen = choice
        return done
    }

    override suspend fun setDownloadProxy(
        id: DownloadId,
        choice: ProxyChoice,
    ) = call("setDownloadProxy ${id.value} $choice")

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

    override suspend fun scheduleRules(): List<ScheduleRule> = scheduleList

    override suspend fun addScheduleRule(new: NewScheduleRule): ScheduleRule {
        call("addScheduleRule")
        return ScheduleRule(ScheduleRuleId(nextRule++), new.days, new.startMinute, new.endMinute, new.action).also {
            scheduleList = scheduleList + it
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
        val there = scheduleList.any { it.id == id }
        scheduleList = scheduleList.filter { it.id != id }
        return there
    }

    override val networks = MutableStateFlow(BondingState(emptyList()))

    override suspend fun setNetworkEnabled(
        key: String,
        enabled: Boolean,
    ): CommandResult {
        val done = call("setNetworkEnabled $key $enabled")
        networks.value =
            BondingState(networks.value.networks.map { if (it.info.key == key) it.copy(enabled = enabled) else it })
        return done
    }

    override val afterQueue = MutableStateFlow(AfterQueueState.Idle)
    override val keepAwake = MutableStateFlow(true)
    override val bridge = MutableStateFlow<BridgeStatus>(BridgeStatus.Off)
    override val clipboardLinks = MutableSharedFlow<ClipboardLink>(extraBufferCapacity = 16)
    override val hotkeyPresses = MutableSharedFlow<String>(extraBufferCapacity = 16)
    override val osSettings = MutableStateFlow(OsSettings())
    override val hotkeyStatus = MutableStateFlow<HotkeyStatus>(HotkeyStatus.Off)

    override suspend fun setClipboardWatch(on: Boolean): CommandResult {
        val done = call("setClipboardWatch $on")
        osSettings.value = osSettings.value.copy(clipboardWatch = on)
        return done
    }

    override suspend fun setGlobalHotkey(on: Boolean): CommandResult {
        val done = call("setGlobalHotkey $on")
        osSettings.value = osSettings.value.copy(globalHotkey = on)
        return done
    }

    var runtime = RuntimeSettings()

    override suspend fun runtimeSettings(): RuntimeSettings = runtime

    override suspend fun setNotifications(on: Boolean): CommandResult {
        val done = call("setNotifications $on")
        runtime = runtime.copy(notifications = on)
        return done
    }

    override suspend fun setBridgeOrigins(origins: Set<String>): CommandResult {
        val done = call("setBridgeOrigins ${origins.sorted().joinToString(",")}")
        if (done is CommandResult.Done) runtime = runtime.copy(bridgeOrigins = origins)
        return done
    }

    override suspend fun setDebugLogging(on: Boolean): CommandResult {
        val done = call("setDebugLogging $on")
        runtime = runtime.copy(debugLogging = on)
        return done
    }

    override suspend fun setYtDlpChannel(channel: YtDlpChannel): CommandResult {
        val done = call("setYtDlpChannel $channel")
        runtime = runtime.copy(ytDlpChannel = channel)
        return done
    }

    override suspend fun setSystemTools(on: Boolean): CommandResult {
        val done = call("setSystemTools $on")
        runtime = runtime.copy(systemTools = on)
        return done
    }

    override suspend fun setToolFolder(
        tool: Tool,
        folder: String?,
    ): CommandResult {
        val done = call("setToolFolder ${tool.id} ${folder ?: "-"}")
        if (done is CommandResult.Done) {
            runtime = runtime.copy(toolFolders = if (folder == null) runtime.toolFolders - tool else runtime.toolFolders + (tool to folder))
        }
        return done
    }

    var toolList: List<ToolStatus> = Tool.entries.map { ToolStatus(it, null) }
    var toolUpdate = ToolUpdate.FETCHING

    override suspend fun toolStatus(): List<ToolStatus> = toolList

    override suspend fun updateTool(tool: Tool): ToolUpdate {
        call("updateTool ${tool.id}")
        return toolUpdate
    }

    /** What exporting a bundle answers: null for it worked. */
    var diagnosticsError: String? = null
    val bundles = mutableListOf<String>()

    override suspend fun exportDiagnostics(target: String): String? {
        call("exportDiagnostics")
        bundles += target
        return diagnosticsError
    }
}

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
    saveFolder: String? = null,
): DownloadSnapshot =
    DownloadSnapshot(
        download =
            Download(
                DownloadId(id),
                if (torrent != null) DownloadKind.TORRENT else DownloadKind.DIRECT,
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
        speedSamples = emptyList(),
        lastFailure = attempts.lastOrNull { it.errorClass != null || it.message != null },
        attempts = attempts,
        mirrors = mirrors,
        files = files,
        items = items,
        torrent = torrent,
    )

/** [FileActions] that only record what they were asked; [failure] is what each answers. */
internal class FakeFiles(
    var failure: String? = null,
) : FileActions {
    val done = mutableListOf<String>()

    override fun open(path: java.nio.file.Path) = failure.also { done += "open $path" }

    override fun reveal(path: java.nio.file.Path) = failure.also { done += "reveal $path" }

    override fun copy(text: String) = failure.also { done += "copy $text" }
}
