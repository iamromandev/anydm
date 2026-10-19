package dev.anydm.engine.media

import dev.anydm.engine.core.SubtitleChoice

private val READABLE = listOf("srt", "vtt", "ass", "ssa")

/** Picks the subtitle files to fetch (MED-13): pure, from what the page lists and what the user asked. */
object SubtitleSelector {
    /**
     * One track per wanted language, in the order asked. A manual track wins; with `includeAuto` and no readable manual
     * track, the machine-made caption of that language (`en-orig` first) is used; a translation made for another
     * language is never taken. A regional variant (`en-US`) stands in for its language. Only formats ffmpeg reads count.
     */
    fun choose(
        tracks: List<SubtitleTrack>,
        choice: SubtitleChoice,
    ): List<SubtitleTrack> {
        val chosen = mutableListOf<SubtitleTrack>()
        val seen = mutableSetOf<String>()
        for (wanted in choice.languages) {
            if (!seen.add(wanted.lowercase())) continue
            val track = manual(tracks, wanted) ?: if (choice.includeAuto) auto(tracks, wanted) else null
            if (track != null) chosen += track
        }
        return chosen
    }

    private fun manual(
        tracks: List<SubtitleTrack>,
        wanted: String,
    ): SubtitleTrack? {
        val manual = tracks.filter { !it.auto }
        val exact = manual.filter { it.lang.equals(wanted, ignoreCase = true) }
        val pool = exact.ifEmpty { manual.filter { it.lang.startsWith("$wanted-", ignoreCase = true) }.sortedBy { it.lang.lowercase() } }
        return best(pool)
    }

    private fun auto(
        tracks: List<SubtitleTrack>,
        wanted: String,
    ): SubtitleTrack? {
        val auto = tracks.filter { it.auto }
        return best(auto.filter { it.lang.equals("$wanted-orig", ignoreCase = true) })
            ?: best(auto.filter { it.lang.equals(wanted, ignoreCase = true) })
    }

    /** The track in the most readable format, or null when none is readable. */
    private fun best(pool: List<SubtitleTrack>): SubtitleTrack? =
        pool.filter { it.ext.lowercase() in READABLE }.minByOrNull { READABLE.indexOf(it.ext.lowercase()) }
}

/** The picture to embed and whether ffmpeg has to turn it into a JPEG first. */
data class ChosenThumbnail(
    val thumbnail: Thumbnail,
    val needsConversion: Boolean,
)

/** Picks the cover picture (MED-15). */
object ThumbnailSelector {
    private val EMBEDDABLE = setOf("jpg", "jpeg", "png")

    /** A JPEG or PNG first, then the higher `preference`, then the larger picture; with only other formats, the best of those, flagged. */
    fun choose(thumbnails: List<Thumbnail>): ChosenThumbnail? {
        val order =
            compareByDescending<Thumbnail> { it.preference ?: 0 }.thenByDescending { (it.width ?: 0).toLong() * (it.height ?: 0) }
        val embeddable = thumbnails.filter { it.ext in EMBEDDABLE }.sortedWith(order).firstOrNull()
        if (embeddable != null) return ChosenThumbnail(embeddable, needsConversion = false)
        val other = thumbnails.filter { it.ext.isNotEmpty() }.sortedWith(order).firstOrNull() ?: return null
        return ChosenThumbnail(other, needsConversion = true)
    }
}
