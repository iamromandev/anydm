package dev.anydm.engine.store

import dev.anydm.engine.core.CookieSource
import dev.anydm.engine.core.Pacing
import dev.anydm.engine.core.ProfileId
import dev.anydm.engine.core.StepRunner
import dev.anydm.engine.files.ClashPolicy
import dev.anydm.engine.net.AfterQueueAction
import dev.anydm.engine.net.ProxyChoice
import dev.anydm.engine.net.SlowMode
import dev.anydm.engine.tools.YtDlpChannel
import kotlinx.io.files.Path

/**
 * The engine's settings (SET-01). Stored one row per setting that differs from its default, so a better default in a
 * later version reaches everyone who never changed it. A new setting is a property here and a name in SqlSettingsStore;
 * it needs no migration. Renaming or re-encoding one does: that ships as SQL in a schema migration (see [SettingsStore]).
 */
data class Settings(
    /** How many downloads may transfer at once (FND-04). */
    val concurrencyLimit: Int = 3,
    /** Where downloads go when nothing else decides; null means the OS Downloads folder (#539 resolves it). */
    val defaultSaveFolder: Path? = null,
    /** Everything is paused, and stays paused after a restart (FND-05). */
    val globalPause: Boolean = false,
    /** How many automatic retries in a row a job gets before it fails (FND-07); 0 never retries. */
    val maxRetries: Int = 5,
    /** Write DEBUG lines to the log too (#556): every status move. */
    val debugLogging: Boolean = false,
    /** The most connections HTTP downloads open to one host, shared by all of them (HTTP-06). */
    val connectionsPerDownload: Int = 4,
    /** The most bytes per second every download together may take in (NET-02); null means no limit. */
    val globalSpeedLimit: Long? = null,
    /** What happens when a finished file's name is taken (FIL-04). */
    val clashPolicy: ClashPolicy = ClashPolicy.RENAME,
    /** Bytes kept free after a download's own (FIL-08): a start needs room for the file plus this, and running jobs pause below it. */
    val freeSpaceReserve: Long = StepRunner.DEFAULT_FREE_SPACE_RESERVE,
    /** How many finished downloads the history keeps (FIL-10); the oldest go first. */
    val historyLimit: Int = 1000,
    /** Which yt-dlp builds to follow (BIN-04). */
    val ytDlpChannel: YtDlpChannel = YtDlpChannel.STABLE,
    /** Use a tool already on the machine (Homebrew, winget, apt) when it is new enough (BIN-03). */
    val systemTools: Boolean = true,
    /** Where yt-dlp reads cookies from, for pages that need a login (MED-18). */
    val cookieSource: CookieSource = CookieSource.None,
    /** How carefully requests to a site are paced (MED-20). */
    val pacing: Pacing = Pacing(),
    /** The port torrents listen on (TOR-04); 0 picks one. */
    val torrentPort: Int = 6881,
    /** A finished torrent seeds until it has sent this many times its size (TOR-05); null for no ratio limit. A torrent can have its own. */
    val seedRatio: Double? = null,
    /** A finished torrent seeds this many minutes (TOR-05); null for no time limit. A torrent can have its own. */
    val seedMinutes: Int? = null,
    /** The most bytes per second every upload together may send (TOR-08); null means no limit. */
    val globalUploadLimit: Long? = null,
    /** Keep an `.m3u8` of each collection's finished items in its folder (MED-17). */
    val playlistFile: Boolean = true,
    /** The profile a new download takes when it names none (SET-02). */
    val defaultProfileId: ProfileId? = null,
    /** Slow mode is on (NET-06): downloads and uploads are capped at [slowModeLimit]. */
    val slowMode: Boolean = false,
    /** The most bytes per second each direction takes in slow mode (NET-06). */
    val slowModeLimit: Long = SlowMode.DEFAULT_LIMIT,
    /** Keep the machine awake while a download runs (NET-11). */
    val keepAwake: Boolean = true,
    /** What to do with the machine once the queue is finished (NET-10). */
    val afterQueue: AfterQueueAction = AfterQueueAction.NONE,
    /** Which proxy downloads go through when neither the download nor its profile says (NET-15); never [ProxyChoice.Inherit]. */
    val proxy: ProxyChoice = ProxyChoice.System,
    /** The networks (interface names) the user turned on for bonding (BND-01); none by default. One that is not present now keeps its place. */
    val bondedNetworks: Set<String> = emptySet(),
    /** Tell the user when a download finishes or fails, or an allowance is reached (OSI-09). */
    val notifications: Boolean = true,
    /**
     * The browser extensions the bridge answers (OSI-01), as `chrome-extension://<id>` or `moz-extension://<id>`. Empty
     * means any extension origin, until the extension's ids are known (#546).
     */
    val bridgeOrigins: Set<String> = emptySet(),
    /** Look at the clipboard for a copied link and announce it (OSI-05). Off until the user asks. */
    val clipboardWatch: Boolean = false,
    /** Listen for the global hotkey that queues the copied link (OSI-06). Off until the user asks. */
    val globalHotkey: Boolean = false,
) {
    init {
        require(concurrencyLimit >= 1) { "concurrencyLimit must be >= 1: $concurrencyLimit" }
        require(proxy != ProxyChoice.Inherit) { "the global proxy has no level to inherit from" }
        require(historyLimit >= 1) { "historyLimit must be >= 1: $historyLimit" }
        require(freeSpaceReserve >= 0) { "freeSpaceReserve must be >= 0: $freeSpaceReserve" }
        require(
            bondedNetworks.all { it.isNotBlank() && it.none { c -> c == '\n' || c == '\r' } },
        ) { "a bonded network needs a name without line breaks: $bondedNetworks" }
        require(bridgeOrigins.all { it.matches(EXTENSION_ORIGIN) }) { "not an extension origin: $bridgeOrigins" }
        require(maxRetries >= 0) { "maxRetries must be >= 0: $maxRetries" }
        require(torrentPort in 0..65535) { "torrentPort must be in 0..65535: $torrentPort" }
        require(connectionsPerDownload in 1..16) { "connectionsPerDownload must be in 1..16: $connectionsPerDownload" }
        require(globalSpeedLimit == null || globalSpeedLimit > 0) { "globalSpeedLimit must be > 0, or null for none: $globalSpeedLimit" }
        require(seedRatio == null || seedRatio > 0) { "seedRatio must be > 0, or null for none: $seedRatio" }
        require(seedMinutes == null || seedMinutes > 0) { "seedMinutes must be > 0, or null for none: $seedMinutes" }
        require(
            globalUploadLimit == null || globalUploadLimit > 0,
        ) { "globalUploadLimit must be > 0, or null for none: $globalUploadLimit" }
        require(slowModeLimit > 0) { "slowModeLimit must be > 0: $slowModeLimit" }
        require(defaultSaveFolder == null || defaultSaveFolder.isAbsolute) {
            "defaultSaveFolder must be absolute: $defaultSaveFolder"
        }
    }

    companion object {
        /** The origin that stands for "no extension": ids are 32 letters, so no browser extension has this one. */
        const val NO_EXTENSION = "chrome-extension://-"

        /** A browser extension's origin: `chrome-extension://<id>` or `moz-extension://<id>`, the id being a plain token. */
        val EXTENSION_ORIGIN = Regex("""(chrome|moz)-extension://[A-Za-z0-9._-]+""")
    }
}
