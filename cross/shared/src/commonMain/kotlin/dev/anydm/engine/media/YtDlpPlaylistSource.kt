package dev.anydm.engine.media

import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.MediaSettings
import dev.anydm.engine.core.ytDlpFlags
import dev.anydm.engine.platform.ProcessRunner
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.tools.ResolvedTool
import dev.anydm.engine.tools.Tool

/**
 * The flat listing of a page by `yt-dlp -J --flat-playlist` (MED-16), with the cookie source and pacing of D. [resolve]
 * finds a tool; [beforeExtract] runs before each listing, where the caller hooks the yt-dlp update check.
 */
class YtDlpPlaylistSource(
    runner: ProcessRunner,
    private val resolve: suspend (Tool) -> ResolvedTool?,
    private val mediaSettings: suspend () -> MediaSettings = { MediaSettings() },
    private val beforeExtract: suspend () -> Unit = {},
) : PlaylistSource {
    private val ytDlp = YtDlpRunner(runner)

    override suspend fun list(url: String): StepResult<PlaylistParse> {
        val tool = resolve(Tool.YT_DLP) ?: return StepResult.Failed(ErrorClass.TOOL_MISSING, Tool.YT_DLP.id)
        // A flat listing needs no JavaScript runtime: Deno is used when it is there, never waited for.
        val deno = if (isYoutube(url)) resolve(Tool.DENO) else null
        val settings = mediaSettings()
        val args = settings.cookieSource.ytDlpFlags() + settings.pacing.values().ytDlpFlags()
        beforeExtract()
        val extracted =
            when (val got = ytDlp.extract(tool, deno, url, args, flat = true)) {
                is StepResult.Failed -> return got
                is StepResult.Ok -> got.value
            }
        if (extracted.json.isEmpty()) return StepResult.Failed(null, "yt-dlp printed no JSON for this page")
        return StepResult.Ok(YtDlpJson.parsePlaylist(extracted.json, url))
    }
}
