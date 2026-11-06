package dev.anydm.engine.tools

import dev.anydm.engine.http.HttpIoException
import dev.anydm.engine.http.HttpRequest
import dev.anydm.engine.http.HttpTransport

private const val MAX_SUMS = 1 shl 20

/** The GitHub repository (`owner/name`) that publishes yt-dlp's [channel]. */
fun ytDlpRepo(channel: YtDlpChannel): String = if (channel == YtDlpChannel.STABLE) "yt-dlp/yt-dlp" else "yt-dlp/yt-dlp-nightly-builds"

/**
 * A repository's releases on GitHub, yt-dlp's by default. The newest tag is where `/releases/latest` redirects to; the SHA-256
 * of each asset is in the release's `SHA2-256SUMS`. Both are small reads, not downloads: the build itself is fetched as a TOOL job.
 * [repoOf] names the repository of a channel and [assetOf] the release asset for a platform (null: none).
 */
class GithubReleases(
    private val transport: HttpTransport,
    private val repoOf: (YtDlpChannel) -> String = ::ytDlpRepo,
    private val assetOf: (Platform) -> String? = ::ytDlpAsset,
) : ReleaseSource {
    override suspend fun latest(
        channel: YtDlpChannel,
        platform: Platform,
    ): Release? {
        val asset = assetOf(platform) ?: return null
        val repo = repoOf(channel)
        val base = "https://github.com/$repo/releases"
        val tag = latestTag(base) ?: return null
        val sums = readCapped(transport, "$base/download/$tag/SHA2-256SUMS") ?: return null
        val sha256 =
            sums
                .lineSequence()
                .mapNotNull(::parseSum)
                .firstOrNull { it.second == asset }
                ?.first ?: return null
        return Release(tag, "$base/download/$tag/$asset", sha256)
    }

    private suspend fun latestTag(base: String): String? {
        val response =
            try {
                transport.open(HttpRequest("$base/latest"))
            } catch (e: HttpIoException) {
                return null
            }
        response.body.close()
        if (response.status != 200 || "/releases/tag/" !in response.finalUrl) return null
        val tag =
            response.finalUrl
                .substringAfter("/releases/tag/")
                .substringBefore('?')
                .trimEnd('/')
        return tag.takeIf { ToolVersion.parse(it) != null }
    }
}

/**
 * The text at [url] (HTTP 200); null for any other status, a network failure, a body longer than [max] bytes (it is not cut: a
 * truncated list of sums could drop or change a line) or a final address (after redirects) that [acceptFinalUrl] refuses. For small
 * files such as a release's sums, not downloads.
 */
suspend fun readCapped(
    transport: HttpTransport,
    url: String,
    max: Int = MAX_SUMS,
    headers: List<Pair<String, String>> = emptyList(),
    acceptFinalUrl: (String) -> Boolean = { true },
): String? {
    val response =
        try {
            transport.open(HttpRequest(url, headers = headers))
        } catch (e: HttpIoException) {
            return null
        }
    try {
        if (response.status != 200 || !acceptFinalUrl(response.finalUrl)) return null
        val chunks = ArrayList<ByteArray>()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val n = response.body.read(buffer)
            if (n < 0) break
            total += n
            // One byte past the cap is enough to know: a cut-off file is no file.
            if (total > max) return null
            chunks.add(buffer.copyOf(n))
        }
        val all = ByteArray(total)
        var at = 0
        for (chunk in chunks) {
            chunk.copyInto(all, at)
            at += chunk.size
        }
        return all.decodeToString()
    } catch (e: HttpIoException) {
        return null
    } finally {
        response.body.close()
    }
}

/** `<64 hex>  <name>` or `<64 hex> *<name>`, as (hash, name); anything else is not a sum. */
fun parseSum(line: String): Pair<String, String>? {
    val hash = line.substringBefore(' ')
    val name = line.substringAfter(' ', "").trimStart(' ', '*').trim()
    val valid = hash.length == 64 && hash.all { it in '0'..'9' || it in 'a'..'f' }
    return if (valid && name.isNotEmpty()) hash to name else null
}
