package dev.anydm.desktop

import dev.anydm.engine.http.HttpTransport
import dev.anydm.engine.tools.Arch
import dev.anydm.engine.tools.OsFamily
import dev.anydm.engine.tools.Platform
import dev.anydm.engine.tools.parseSum
import dev.anydm.engine.tools.readCapped
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** The installer a release carries for this system: [sha256] is what its release's `SHA2-256SUMS` says; [url] is on the release. */
data class AppAsset(
    val name: String,
    val url: String,
    /** The bytes GitHub reports, or null. */
    val size: Long?,
    val sha256: String,
)

/** A published release that can be installed here: its [version], [tag], release page and the [asset] to download and verify. */
data class AppRelease(
    val version: Version,
    val tag: String,
    val pageUrl: String,
    val asset: AppAsset,
)

/** What looking for the newest anydm release found. */
sealed interface ReleaseCheck {
    /** The newest stable release, with an installer for this system and its checksum. */
    data class Found(
        val release: AppRelease,
    ) : ReleaseCheck

    /** The newest stable release has no installer for this system, or no checksums to verify one by: the user gets it from the website. */
    data class CannotInstall(
        val version: Version,
        val tag: String,
        val pageUrl: String,
        val reason: String,
    ) : ReleaseCheck

    /** No stable release has been published. */
    data object None : ReleaseCheck

    /** The releases could not be read (offline, rate limited, an answer that is not what GitHub sends). */
    data object Failed : ReleaseCheck
}

/** Where the newest anydm release comes from. */
fun interface AppReleaseSource {
    suspend fun latest(): ReleaseCheck
}

/**
 * anydm's releases on GitHub, read from its API over HTTPS. Drafts, prereleases and tags that are no plain version are skipped; the
 * newest of the rest is the release. Only assets that live at `github.com/<repository>/releases/download/<tag>/<name>` count, under a
 * plain file name, and the installer must be listed in the release's `SHA2-256SUMS` (read, at most 1 MB, from the same place).
 * An installer whose name carries no CPU is taken for the one the release pipeline builds on an Apple silicon runner (the macOS dmg)
 * or for x64 (msi, deb): it is not offered to an Intel Mac, which gets [ReleaseCheck.CannotInstall] and the release page.
 * Nothing here downloads an installer; see [Updater].
 */
class AppReleases(
    private val transport: HttpTransport,
    private val platform: Platform,
    private val repository: String = REPOSITORY,
) : AppReleaseSource {
    override suspend fun latest(): ReleaseCheck {
        val body =
            readCapped(
                transport,
                "https://api.github.com/repos/$repository/releases?per_page=10",
                max = MAX_LISTING,
                headers = listOf("Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28"),
                acceptFinalUrl = ::isGithubHttps,
            ) ?: return ReleaseCheck.Failed
        val releases =
            try {
                Json.parseToJsonElement(body) as? JsonArray
            } catch (e: IllegalArgumentException) {
                null
            } ?: return ReleaseCheck.Failed
        val newest =
            releases
                .mapNotNull(::candidate)
                .maxWithOrNull(compareBy { it.version }) ?: return ReleaseCheck.None
        return check(newest)
    }

    private class Candidate(
        val tag: String,
        val version: Version,
        val assets: List<JsonObject>,
    )

    private fun candidate(element: JsonElement): Candidate? {
        val release = element as? JsonObject ?: return null
        if (release.flag("draft") != false || release.flag("prerelease") != false) return null
        val tag = release.text("tag_name") ?: return null
        val version = Version.parse(tag)?.takeUnless { it.isPreRelease } ?: return null
        if (tag != tag.trim()) return null
        val assets = (release["assets"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        return Candidate(tag, version, assets)
    }

    private suspend fun check(release: Candidate): ReleaseCheck {
        val pageUrl = "https://github.com/$repository/releases/tag/${release.tag}"

        fun cannot(reason: String) = ReleaseCheck.CannotInstall(release.version, release.tag, pageUrl, reason)
        val onRelease = release.assets.mapNotNull { onThisRelease(release.tag, it) }
        val sumsAsset =
            onRelease.firstOrNull { it.name == SUMS } ?: return cannot("This release has no checksums, so it can't be verified.")
        val sums =
            readCapped(transport, sumsAsset.url, max = MAX_SUMS, acceptFinalUrl = ::isGithubHttps)
                ?: return ReleaseCheck.Failed
        val hashes = hashesOf(sums)
        val installers = onRelease.filter { extensionOf(it.name) != null && it.name in hashes }
        val installer = pick(installers) ?: return cannot("This release has no installer for this system.")
        val asset = AppAsset(installer.name, installer.url, installer.size, hashes.getValue(installer.name))
        return ReleaseCheck.Found(AppRelease(release.version, release.tag, pageUrl, asset))
    }

    private class Listed(
        val name: String,
        val url: String,
        val size: Long?,
    )

    /** The asset when its file name is plain and its address is exactly the one of that name on the release [tag]; else null. */
    private fun onThisRelease(
        tag: String,
        asset: JsonObject,
    ): Listed? {
        val name = asset.text("name") ?: return null
        val url = asset.text("browser_download_url") ?: return null
        if (!isPlainAssetName(name)) return null
        if (url != assetUrl(repository, tag, name) || !isGithubHttps(url)) return null
        return Listed(name, url, (asset["size"] as? JsonPrimitive)?.longOrNull)
    }

    private fun extensionOf(name: String): String? {
        val wanted = installerExtension(platform.os)
        return wanted.takeIf { name.endsWith(it, ignoreCase = true) }
    }

    // An installer made for this CPU first, then one that names none; one made for another CPU never. An installer that names none
    // is not offered to an Intel Mac: the release's dmg is built on an Apple silicon runner and carries no architecture in its name.
    private fun pick(installers: List<Listed>): Listed? {
        val intelMac = platform.os == OsFamily.MACOS && platform.arch == Arch.X64
        return installers
            .mapNotNull { installer ->
                val arch = archOf(installer.name)
                when {
                    arch == platform.arch -> installer to true
                    arch == null && !intelMac -> installer to false
                    else -> null
                }
            }.sortedWith(compareByDescending<Pair<Listed, Boolean>> { it.second }.thenBy { it.first.name })
            .firstOrNull()
            ?.first
    }

    private fun hashesOf(sums: String): Map<String, String> {
        val all = sums.lineSequence().mapNotNull(::parseSum).groupBy({ it.second }, { it.first })
        // A name listed with two different sums is not to be trusted with either.
        return all.filterValues { it.distinct().size == 1 }.mapValues { it.value.first() }
    }

    companion object {
        const val REPOSITORY = "iamromandev/anydm"
        const val SUMS = "SHA2-256SUMS"
        private const val MAX_SUMS = 1 shl 20
        private const val MAX_LISTING = 2 shl 20
        private val plainName = Regex("""[A-Za-z0-9][A-Za-z0-9._+~-]{0,199}""")

        /** Whether [name] is a plain file name: letters, digits and `._+~-`, no path part, nothing hidden or starting with a dash. */
        fun isPlainAssetName(name: String): Boolean = plainName.matches(name) && ".." !in name

        /** The address GitHub serves [name] of release [tag] of [repository] from: the only one an installer is fetched from. */
        fun assetUrl(
            repository: String,
            tag: String,
            name: String,
        ): String = "https://github.com/$repository/releases/download/$tag/$name"

        private val armName = Regex("""(?<![a-z0-9])(arm64|aarch64)(?![a-z0-9])""")
        private val intelName = Regex("""(?<![a-z0-9])(amd64|x64|x86_64|x86-64)(?![a-z0-9])""")

        /** The installer file type of the Compose packaging on [os]. */
        fun installerExtension(os: OsFamily): String =
            when (os) {
                OsFamily.MACOS -> ".dmg"
                OsFamily.WINDOWS -> ".msi"
                OsFamily.LINUX -> ".deb"
            }

        private fun archOf(name: String): Arch? {
            val lower = name.lowercase()
            return when {
                armName.containsMatchIn(lower) -> Arch.ARM64
                intelName.containsMatchIn(lower) -> Arch.X64
                else -> null
            }
        }

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        // GitHub always sends both flags; one that is missing or no boolean is null, which the caller refuses (fail closed).
        private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    }
}

private val githubHosts =
    setOf(
        "github.com",
        "api.github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
        "github-releases.githubusercontent.com",
    )

/**
 * Whether [url] is an https address on one of the hosts a release is read or served from: `github.com`, `api.github.com` and the
 * `githubusercontent.com` hosts release downloads end up on (`objects.`, `release-assets.`, `github-releases.`). The standard port
 * only, no user name, a lowercase `https` scheme, and none of the other `githubusercontent.com` hosts, which serve what anyone can push
 * (`raw.`, `gist.`).
 *
 * This looks at the address a request ended on, after the transport followed its redirects, not at each hop on the way: the hash
 * in the `SHA2-256SUMS` of the release is what keeps a download honest.
 */
fun isGithubHttps(url: String): Boolean {
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false
    return uri.scheme == "https" && uri.userInfo == null && (uri.port == -1 || uri.port == 443) && host in githubHosts
}
