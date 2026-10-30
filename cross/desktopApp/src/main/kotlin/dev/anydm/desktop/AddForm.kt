package dev.anydm.desktop

import dev.anydm.engine.core.AudioFormat
import dev.anydm.engine.core.CategoryId
import dev.anydm.engine.core.Format
import dev.anydm.engine.core.ItemSelection
import dev.anydm.engine.core.ProfileId
import dev.anydm.engine.core.SponsorBlockChoice
import dev.anydm.engine.core.SponsorBlockMode
import dev.anydm.engine.core.SubtitleChoice
import dev.anydm.engine.http.Expansion
import dev.anydm.engine.http.expandPattern
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import java.net.URLEncoder
import kotlin.time.Instant

/** One line of what the user pasted: a link to try, or a pattern that names none and why. */
sealed interface InputLine {
    val text: String

    data class Link(
        override val text: String,
    ) : InputLine

    data class Invalid(
        override val text: String,
        val reason: String,
    ) : InputLine
}

/** The lines of [text]: trimmed, blanks dropped, batch patterns expanded, and each link once. */
internal fun parseInput(text: String): List<InputLine> {
    val lines =
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.flatMap { line ->
            when (val expanded = expandPattern(line)) {
                is Expansion.Links -> expanded.urls.map { InputLine.Link(it) }
                is Expansion.Invalid -> listOf(InputLine.Invalid(line, expanded.message))
            }
        }
    return lines.distinctBy { it.text }
}

enum class StartChoice(
    val label: String,
) {
    NOW("Now"),
    PAUSED("Paused"),
    AT("At a time"),
}

/** A web link as a file, or as a page yt-dlp extracts media from. */
enum class AddMode { FILE, MEDIA }

enum class MediaPreset(
    val label: String,
    val height: Int? = null,
    val audio: AudioFormat? = null,
) {
    BEST("Best"),
    P2160("2160p", height = 2160),
    P1080("1080p", height = 1080),
    P720("720p", height = 720),
    P480("480p", height = 480),
    MP3("Audio (mp3)", audio = AudioFormat.MP3),
    M4A("Audio (m4a)", audio = AudioFormat.M4A),
    OPUS("Audio (opus)", audio = AudioFormat.OPUS),
}

enum class SponsorChoice(
    val label: String,
) {
    OFF("Off"),
    MARK("Mark"),
    CUT("Cut"),
}

/** What one add applies to every link in it. [startAt] is `yyyy-MM-dd HH:mm`, local time. */
data class AddOptions(
    val categoryId: CategoryId? = null,
    val profileId: ProfileId? = null,
    val folder: String = "",
    val start: StartChoice = StartChoice.NOW,
    val startAt: String = "",
    val mode: AddMode = AddMode.FILE,
    val preset: MediaPreset = MediaPreset.BEST,
    val subtitles: String = "",
    val autoSubtitles: Boolean = false,
    val sponsor: SponsorChoice = SponsorChoice.OFF,
    val playlist: Boolean = false,
    val range: String = "",
    val limit: String = "",
    val user: String = "",
    val password: String = "",
    val keyPath: String = "",
    val passphrase: String = "",
) {
    // The password and passphrase stay out of logs and test output.
    override fun toString(): String = "AddOptions(mode=$mode, folder=$folder, start=$start, user=$user, password=***)"
}

enum class Field { FOLDER, START_AT, RANGE, LIMIT, SUBTITLES, LOGIN }

/** What is wrong with [options], by field; empty when they can be added. [isFolder] tells whether a path is a folder. */
internal fun validate(
    options: AddOptions,
    now: Instant,
    zone: TimeZone,
    isFolder: (String) -> Boolean,
): Map<Field, String> =
    buildMap {
        if (options.folder.isNotBlank() && !isFolder(options.folder.trim())) put(Field.FOLDER, "Not a folder on this computer.")
        if (options.start == StartChoice.AT) {
            val at = startInstant(options, zone)
            when {
                at == null -> put(Field.START_AT, "Write it as 2026-10-31 08:00.")
                at <= now -> put(Field.START_AT, "That time has passed.")
            }
        }
        // The engine reads a login from the address, where a user can't hold ':' or a control character.
        if (options.user.any { it == ':' || it.isISOControl() }) put(Field.LOGIN, "A user name can't hold ':' or a line break.")
        if (options.keyPath.isNotBlank() && options.user.isBlank()) put(Field.LOGIN, "A key file needs the user it signs in as.")
        if (options.mode == AddMode.MEDIA) {
            if (languages(options).isNotEmpty() && runCatching { SubtitleChoice(languages(options)) }.isFailure) {
                put(Field.SUBTITLES, "Language codes, such as en, de or pt-BR.")
            }
            if (options.playlist) {
                if (options.range.isNotBlank() && runCatching { ItemSelection(range = options.range.trim()) }.isFailure) {
                    put(Field.RANGE, "Item numbers and ranges, such as 1:5,8,10:")
                }
                if (options.limit.isNotBlank() && runCatching { ItemSelection(limit = options.limit.trim().toInt()) }.isFailure) {
                    put(Field.LIMIT, "A whole number above 0.")
                }
            }
        }
    }

/** When a download set to start later starts, or null when it starts now or the time can't be read. */
internal fun startInstant(
    options: AddOptions,
    zone: TimeZone,
): Instant? {
    if (options.start != StartChoice.AT) return null
    val text = options.startAt.trim().replace(' ', 'T')
    return runCatching { LocalDateTime.parse(text).toInstant(zone) }.getOrNull()
}

private fun languages(options: AddOptions): List<String> =
    options.subtitles
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

/** The engine's format for the media choices. */
internal fun toFormat(options: AddOptions): Format =
    Format(
        height = options.preset.height,
        audioOnly = options.preset.audio,
        subtitles = languages(options).takeIf { it.isNotEmpty() }?.let { SubtitleChoice(it, includeAuto = options.autoSubtitles) },
        sponsorBlock =
            when (options.sponsor) {
                SponsorChoice.OFF -> null
                SponsorChoice.MARK -> SponsorBlockChoice(SponsorBlockMode.MARK)
                SponsorChoice.CUT -> SponsorBlockChoice(SponsorBlockMode.CUT)
            },
    )

/** Which items of a playlist to add. Call it only when [validate] found no range or limit error. */
internal fun selection(options: AddOptions): ItemSelection =
    ItemSelection(
        range = options.range.trim().ifEmpty { null },
        limit =
            options.limit
                .trim()
                .ifEmpty { null }
                ?.toInt(),
    )

/**
 * [url] with the login put in, percent-encoded; the engine takes it out again into the keychain when the job is added. An
 * address that already holds a login keeps it, and without a user nothing is added.
 */
internal fun withLogin(
    url: String,
    user: String,
    password: String,
): String {
    val scheme = url.substringBefore("://", missingDelimiterValue = "")
    if (scheme.isEmpty() || user.isBlank()) return url
    val rest = url.substringAfter("://")
    if ('@' in rest.substringBefore('/')) return url
    val login = encode(user.trim()) + if (password.isNotEmpty()) ":" + encode(password) else ""
    return "$scheme://$login@$rest"
}

private fun encode(text: String) = URLEncoder.encode(text, Charsets.UTF_8).replace("+", "%20")

/** [url] as it may be shown: without a login in it. */
internal fun withoutLogin(url: String): String {
    val scheme = url.substringBefore("://", missingDelimiterValue = "")
    if (scheme.isEmpty()) return url
    val rest = url.substringAfter("://")
    val authority = rest.substringBefore('/')
    if ('@' !in authority) return url
    return "$scheme://" + authority.substringAfterLast('@') + rest.removePrefix(authority)
}

/** The server an FTP address names, for telling whether one login fits every link. */
internal fun hostOf(url: String): String = withoutLogin(url).substringAfter("://").substringBefore('/').lowercase()
