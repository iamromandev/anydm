package dev.anydm.engine.net

import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.ProfileId
import dev.anydm.engine.files.Profile
import dev.anydm.engine.ftp.Keychain
import dev.anydm.engine.ftp.KeychainUnavailable
import dev.anydm.engine.platform.ProxySource
import dev.anydm.engine.platform.ProxySourceFailure
import dev.anydm.engine.store.Store

/** The proxy the OS asks for (NET-15), without a login. */
data class SystemProxy(
    val type: ProxyType,
    val host: String,
    val port: Int,
)

/** What a connection carries, which decides the proxy types that can serve it. */
enum class ProxyUse { HTTP, FTP, SFTP, MEDIA, TORRENT }

/**
 * This route if [use] can go through it, else [ProxyRoute.Blocked]: a connection is never sent direct because its proxy
 * can't carry it. OkHttp and yt-dlp have no TLS to the proxy, so an HTTPS proxy serves only FTP and SFTP (our own
 * sockets); libtorrent's peers go through SOCKS5 alone.
 */
fun ProxyRoute.checkedFor(use: ProxyUse): ProxyRoute {
    if (this !is ProxyRoute.Via) return this
    val type = endpoint.type
    val supported =
        when (use) {
            ProxyUse.FTP, ProxyUse.SFTP -> true
            ProxyUse.HTTP, ProxyUse.MEDIA -> type != ProxyType.HTTPS
            ProxyUse.TORRENT -> type == ProxyType.SOCKS5
        }
    return if (supported) this else ProxyRoute.Blocked("a $type proxy can't carry ${use.label}")
}

private val ProxyUse.label: String
    get() =
        when (this) {
            ProxyUse.HTTP -> "HTTP downloads"
            ProxyUse.FTP -> "FTP downloads"
            ProxyUse.SFTP -> "SFTP downloads"
            ProxyUse.MEDIA -> "media downloads"
            ProxyUse.TORRENT -> "torrents"
        }

/** The first choice that isn't [ProxyChoice.Inherit]: the download's, the profile's, then the [global] one (SET-02 order). */
fun effectiveProxy(
    job: Download,
    profile: Profile?,
    global: ProxyChoice,
): ProxyChoice = effectiveProxy(job.proxy, profile, global)

/** [effectiveProxy] for a job that has only [own] to say (a download not added yet). */
fun effectiveProxy(
    own: ProxyChoice,
    profile: Profile?,
    global: ProxyChoice,
): ProxyChoice =
    own
        .takeIf { it != ProxyChoice.Inherit }
        ?: profile?.proxy?.takeIf { it != ProxyChoice.Inherit }
        ?: global

/** The host of [url] with no user, password, port or brackets, or null when it has none (a magnet link). */
internal fun proxyHostOf(url: String): String? {
    if ("://" !in url) return null
    val authority =
        url
            .substringAfter("://")
            .substringBefore('/')
            .substringBefore('\\')
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('@')
    val host =
        if (authority.startsWith("[")) {
            authority.substringAfter('[').substringBefore(']')
        } else {
            authority.substringBefore(':')
        }
    return host.ifEmpty { null }
}

/**
 * Decides each download's route from the store (NET-15), live: the choice of the download, its profile and the global
 * setting, the proxy's bypass list, and its password from the keychain. Anything that stops a chosen proxy from being
 * used (the proxy is gone, its password is missing, the keychain is locked) is [ProxyRoute.Blocked], never direct.
 */
class StoreProxyRouter(
    private val store: Store,
    private val keychain: Keychain,
    private val system: ProxySource = ProxySource.None,
) : ProxyRouter {
    override suspend fun route(
        downloadId: DownloadId,
        url: String,
    ): ProxyRoute {
        val job = store.downloads.get(downloadId) ?: return ProxyRoute.Blocked("the download is gone")
        return resolve(job.proxy, job.profileId, url)
    }

    override suspend fun routeNew(
        choice: ProxyChoice,
        profile: ProfileId?,
        url: String,
    ): ProxyRoute = resolve(choice, profile ?: store.settings.load().defaultProfileId, url)

    private suspend fun resolve(
        own: ProxyChoice,
        profileId: ProfileId?,
        url: String,
    ): ProxyRoute {
        val profile = profileId?.let { store.profiles.get(it) }
        // A profile that is named and can't be read might have held a proxy: not a reason to fall through to another.
        if (profileId != null && profile == null) return ProxyRoute.Blocked("the profile can't be read")
        val host = proxyHostOf(url)
        return when (val choice = effectiveProxy(own, profile, store.settings.load().proxy)) {
            ProxyChoice.Inherit, ProxyChoice.Direct -> ProxyRoute.Direct
            ProxyChoice.System -> fromSystem(url, host)
            is ProxyChoice.Named -> named(choice, host)
        }
    }

    private suspend fun fromSystem(
        url: String,
        host: String?,
    ): ProxyRoute {
        if (host != null && BypassList.parse(emptyList()).matches(host)) return ProxyRoute.Direct
        val proxy =
            try {
                system.select(url)
            } catch (e: ProxySourceFailure) {
                return ProxyRoute.Blocked("the system proxy settings can't be read for this address: ${e.message}")
            } ?: return ProxyRoute.Direct
        return ProxyRoute.Via(ProxyEndpoint(proxy.type, proxy.host, proxy.port))
    }

    private suspend fun named(
        choice: ProxyChoice.Named,
        host: String?,
    ): ProxyRoute {
        val proxy = store.proxies.get(choice.id) ?: return ProxyRoute.Blocked("the proxy is gone")
        if (host != null && BypassList.parse(proxy.bypass).matches(host)) return ProxyRoute.Direct
        val password =
            if (proxy.username == null) {
                null
            } else {
                try {
                    keychain.get(proxy.account)
                } catch (_: KeychainUnavailable) {
                    return ProxyRoute.Blocked("the keychain can't be reached for the password of proxy ${proxy.name}")
                } ?: return ProxyRoute.Blocked("proxy ${proxy.name} has no password saved")
            }
        return ProxyRoute.Via(ProxyEndpoint(proxy.type, proxy.host, proxy.port, proxy.username, password))
    }
}

/** The keychain of an engine that was given none: every password is out of reach, so a proxy that needs one is blocked. */
internal object NoKeychain : Keychain {
    override suspend fun get(account: String): String? = throw KeychainUnavailable("no keychain")

    override suspend fun put(
        account: String,
        secret: String,
    ): Unit = throw KeychainUnavailable("no keychain")

    override suspend fun remove(account: String): Unit = throw KeychainUnavailable("no keychain")
}
