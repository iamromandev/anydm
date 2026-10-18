package dev.anydm.engine.media

import dev.anydm.engine.core.Format
import dev.anydm.engine.core.RequestContext
import dev.anydm.engine.files.NameFields
import dev.anydm.engine.files.TemplateField
import dev.anydm.engine.http.HttpProbe
import dev.anydm.engine.protocol.ProbeResult

private const val MAX_STEM = 120
private val UNSAFE = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

/**
 * What probing a media page learned (MED-01): the page's [info], the [selection] made for [format], and each chosen
 * stream's HTTP probe by format id. [name] is the output's: the title and the output extension.
 */
class SiteProbe(
    val info: MediaInfo,
    val selection: Selection,
    val format: Format,
    /** The page's address, for extracting again. */
    val url: String,
    val context: RequestContext?,
    val probes: Map<String, HttpProbe>,
    override val name: String,
    override val size: Long?,
    override val validator: String?,
    override val nameFields: NameFields,
) : ProbeResult

/** The streams' sizes added up, or null when any is unknown. */
internal fun totalSize(probes: List<HttpProbe>): Long? {
    var total = 0L
    for (probe in probes) total += probe.size ?: return null
    return total
}

internal fun nameFieldsOf(
    info: MediaInfo,
    selection: Selection,
): NameFields =
    NameFields(
        buildMap {
            put(TemplateField.TITLE, info.title)
            info.uploader?.let { put(TemplateField.UPLOADER, it) }
            put(TemplateField.ID, info.id)
            info.date?.takeIf { it.length == 8 && it.all(Char::isDigit) }?.let {
                put(TemplateField.DATE, "${it.substring(0, 4)}-${it.substring(4, 6)}-${it.substring(6)}")
            }
            selection.video?.height?.let { put(TemplateField.RESOLUTION, "${it}p") }
        },
    )

/**
 * [title] as a file name stem: path separators, characters Windows refuses, control characters and unpaired surrogates
 * become `_`, whitespace runs one space, dots and spaces at the ends go, and it is cut to [MAX_STEM] characters without
 * splitting a pair. Falls back to [fallback], then "media". The placement's name cleaner still has the last word.
 */
internal fun fileStem(
    title: String,
    fallback: String,
): String = cleanStem(title).ifBlank { cleanStem(fallback) }.ifBlank { "media" }

private fun cleanStem(text: String): String {
    val spaced = text.replace(Regex("\\s+"), " ")
    val out = StringBuilder()
    var i = 0
    while (i < spaced.length) {
        val c = spaced[i]
        when {
            c.isHighSurrogate() && i + 1 < spaced.length && spaced[i + 1].isLowSurrogate() -> {
                out.append(c).append(spaced[i + 1])
                i++
            }

            c.isSurrogate() || c in UNSAFE || c.isISOControl() -> {
                out.append('_')
            }

            else -> {
                out.append(c)
            }
        }
        i++
    }
    var stem = out.toString().trim().trim('.', ' ')
    if (stem.length > MAX_STEM) {
        stem = stem.take(MAX_STEM)
        if (stem.last().isHighSurrogate()) stem = stem.dropLast(1)
        stem = stem.trim().trim('.', ' ')
    }
    return stem
}
