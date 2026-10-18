package dev.anydm.engine.tools

import dev.anydm.engine.http.HttpIoException
import dev.anydm.engine.http.HttpRequest
import dev.anydm.engine.http.HttpTransport

private const val MAX_SUMS = 1 shl 20

/**
 * yt-dlp's releases on GitHub. The newest tag is where `/releases/latest` redirects to; the SHA-256 of each asset is in
 * the release's `SHA2-256SUMS`. Both are small reads, not downloads: the build itself is fetched as a TOOL job.
 */
class GithubReleases(
    private val transport: HttpTransport,
) : ReleaseSource {
    override suspend fun latest(
        channel: YtDlpChannel,
        platform: Platform,
    ): Release? {
        val asset = ytDlpAsset(platform) ?: return null
        val repo = if (channel == YtDlpChannel.STABLE) "yt-dlp/yt-dlp" else "yt-dlp/yt-dlp-nightly-builds"
        val base = "https://github.com/$repo/releases"
        val tag = latestTag(base) ?: return null
        val sums = text("$base/download/$tag/SHA2-256SUMS") ?: return null
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

    private suspend fun text(url: String): String? {
        val response =
            try {
                transport.open(HttpRequest(url))
            } catch (e: HttpIoException) {
                return null
            }
        try {
            if (response.status != 200) return null
            var all = ByteArray(0)
            val buffer = ByteArray(8192)
            while (all.size < MAX_SUMS) {
                val n = response.body.read(buffer)
                if (n < 0) break
                all += buffer.copyOf(n)
            }
            return all.decodeToString()
        } catch (e: HttpIoException) {
            return null
        } finally {
            response.body.close()
        }
    }

    /** `<64 hex>  <name>` or `<64 hex> *<name>`; anything else is not a sum. */
    private fun parseSum(line: String): Pair<String, String>? {
        val hash = line.substringBefore(' ')
        val name = line.substringAfter(' ', "").trimStart(' ', '*').trim()
        val valid = hash.length == 64 && hash.all { it in '0'..'9' || it in 'a'..'f' }
        return if (valid && name.isNotEmpty()) hash to name else null
    }
}
