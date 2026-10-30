package dev.anydm.desktop

import dev.anydm.engine.core.AudioFormat
import dev.anydm.engine.core.CategoryId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.Format
import dev.anydm.engine.core.Pacing
import dev.anydm.engine.core.PacingPreset
import dev.anydm.engine.core.PacingValues
import dev.anydm.engine.files.Category
import dev.anydm.engine.files.CategoryRule
import dev.anydm.engine.files.ClashPolicy
import dev.anydm.engine.files.NameTemplate
import dev.anydm.engine.files.NewCategory
import dev.anydm.engine.files.NewProfile
import dev.anydm.engine.files.Profile
import dev.anydm.engine.files.TemplateField
import dev.anydm.engine.files.TemplateParse
import dev.anydm.engine.net.AfterQueueAction
import dev.anydm.engine.net.ProxyChoice
import dev.anydm.engine.store.TransferSettings
import java.nio.file.Path

// The settings screen's forms (#608): text as typed, checked against the engine's own limits before anything is sent.

enum class Section(
    val label: String,
) {
    FILES("Downloads & files"),
    SPEED("Connections & speed"),
    QUEUE("Queue"),
    CATEGORIES("Categories"),
    PROFILES("Profiles"),
    HISTORY("History & traffic"),
    BACKUP("Backup"),
    PROXIES("Proxies"),
    RULES("Network rules"),
    NETWORKS("Networks"),
    SYSTEM("Notifications & browser"),
    TOOLS("Tools"),
    DIAGNOSTICS("Diagnostics"),
    ;

    /** The three sections that edit [TransferForm] and share its Save. */
    val transfer: Boolean get() = this == FILES || this == SPEED || this == QUEUE
}

/** What happens once the queue is done. Quit is the app's own; the engine does the others. */
enum class AfterQueueChoice(
    val label: String,
    val action: AfterQueueAction,
) {
    NOTHING("Nothing", AfterQueueAction.NONE),
    QUIT("Quit anydm", AfterQueueAction.NONE),
    SLEEP("Sleep", AfterQueueAction.SLEEP),
    SHUT_DOWN("Shut down", AfterQueueAction.SHUT_DOWN),
}

enum class SettingField {
    FOLDER,
    RESERVE,
    RETRIES,
    HISTORY,
    PACING,
    CONNECTIONS,
    DOWN_LIMIT,
    UP_LIMIT,
    SLOW_LIMIT,
    SEED_RATIO,
    SEED_MINUTES,
    CONCURRENCY,
}

internal const val KB = 1024L
internal const val MB = 1024L * 1024
private const val MAX_PACING = 120.0

/** The transfer and file settings as typed. Speeds are KB/s and the reserve MB; an empty limit is none. */
data class TransferForm(
    val folder: String = "",
    val clash: ClashPolicy = ClashPolicy.RENAME,
    val reserveMb: String = "64",
    val playlistFile: Boolean = true,
    val retries: String = "5",
    val historyLimit: String = "1000",
    val pacing: PacingPreset = PacingPreset.OFF,
    val pacingSleep: String = "0",
    val pacingMin: String = "0",
    val pacingMax: String = "0",
    val connections: String = "4",
    val downKbps: String = "",
    val upKbps: String = "",
    val slowMode: Boolean = false,
    val slowKbps: String = "1024",
    val seedRatio: String = "",
    val seedMinutes: String = "",
    val concurrency: String = "3",
    val globalPause: Boolean = false,
    val keepAwake: Boolean = true,
    val afterQueue: AfterQueueChoice = AfterQueueChoice.NOTHING,
)

fun transferForm(
    settings: TransferSettings,
    quit: Boolean = false,
): TransferForm {
    val custom = settings.pacing.custom
    return TransferForm(
        folder = settings.defaultSaveFolder.orEmpty(),
        clash = settings.clashPolicy,
        reserveMb = (settings.freeSpaceReserve / MB).toString(),
        playlistFile = settings.playlistFile,
        retries = settings.maxRetries.toString(),
        historyLimit = settings.historyLimit.toString(),
        pacing = settings.pacing.preset,
        pacingSleep = seconds(custom.requestSleep),
        pacingMin = seconds(custom.intervalMin),
        pacingMax = seconds(custom.intervalMax),
        connections = settings.connectionsPerDownload.toString(),
        downKbps = settings.globalSpeedLimit?.let(::kbps).orEmpty(),
        upKbps = settings.globalUploadLimit?.let(::kbps).orEmpty(),
        slowMode = settings.slowMode,
        slowKbps = kbps(settings.slowModeLimit),
        seedRatio = settings.seedRatio?.toString().orEmpty(),
        seedMinutes = settings.seedMinutes?.toString().orEmpty(),
        concurrency = settings.concurrencyLimit.toString(),
        globalPause = settings.globalPause,
        keepAwake = settings.keepAwake,
        afterQueue =
            if (quit) {
                AfterQueueChoice.QUIT
            } else {
                AfterQueueChoice.entries.first { it.action == settings.afterQueue && it != AfterQueueChoice.QUIT }
            },
    )
}

// Bytes a second as KB/s, rounded up: a limit under 1 KB/s shows as 1, not as a 0 the form would refuse. Saving an
// untouched field keeps the engine's own value (see [mergeEdits] and [toProfile]).
internal fun kbps(bytes: Long): String = ((bytes + KB - 1) / KB).toString()

private fun seconds(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

/** Why each field the engine would refuse is wrong; empty when the form can be saved. */
fun TransferForm.validate(folderExists: (String) -> Boolean): Map<SettingField, String> =
    buildMap {
        val folder = folder.trim()
        if (folder.isNotEmpty()) {
            when {
                !isFullPath(folder) -> put(SettingField.FOLDER, "Give a full path, or leave it empty for Downloads.")
                !folderExists(folder) -> put(SettingField.FOLDER, "There is no such folder.")
            }
        }
        whole(reserveMb, 0, Long.MAX_VALUE / MB)?.let { put(SettingField.RESERVE, it) }
        whole(retries, 0)?.let { put(SettingField.RETRIES, it) }
        whole(historyLimit, 1)?.let { put(SettingField.HISTORY, it) }
        whole(connections, 1, 16)?.let { put(SettingField.CONNECTIONS, it) }
        limit(downKbps)?.let { put(SettingField.DOWN_LIMIT, it) }
        limit(upKbps)?.let { put(SettingField.UP_LIMIT, it) }
        whole(slowKbps, 1, Long.MAX_VALUE / KB)?.let { put(SettingField.SLOW_LIMIT, it) }
        if (seedRatio.isNotBlank() && (seedRatio.trim().toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() } == null)) {
            put(SettingField.SEED_RATIO, "A number above 0, or empty for no limit.")
        }
        if (seedMinutes.isNotBlank()) whole(seedMinutes, 1)?.let { put(SettingField.SEED_MINUTES, "$it Or empty for no limit.") }
        whole(concurrency, 1)?.let { put(SettingField.CONCURRENCY, it) }
        if (pacing == PacingPreset.CUSTOM) {
            val values = listOf(pacingSleep, pacingMin, pacingMax).map { it.trim().toDoubleOrNull() }
            when {
                values.any { it == null || !it.isFinite() || it < 0 || it > MAX_PACING } -> {
                    put(SettingField.PACING, "Seconds from 0 to ${MAX_PACING.toInt()}.")
                }

                values[1]!! > values[2]!! -> {
                    put(SettingField.PACING, "The shortest wait can't be longer than the longest.")
                }
            }
        }
    }

// Null when [text] is a whole number in [min]..[max], else why not. The default [max] is what the engine's Int takes.
internal fun whole(
    text: String,
    min: Long,
    max: Long = Int.MAX_VALUE.toLong(),
): String? {
    val value = text.trim().toLongOrNull()
    return when {
        value == null -> "A whole number."
        value < min -> if (max > SMALL) "At least $min." else "From $min to $max."
        value > max -> if (max > SMALL) "That is too large." else "From $min to $max."
        else -> null
    }
}

// Above this a range isn't worth spelling out.
private const val SMALL = 1_000_000L

// A speed in KB/s, which becomes bytes.
internal fun limit(text: String): String? =
    if (text.isBlank()) null else whole(text, 1, Long.MAX_VALUE / KB)?.let { "$it Or empty for no limit." }

/** [text] is a full path this system can take; false, not an exception, for one it can't (a NUL, or `?` on Windows). */
internal fun isFullPath(text: String): Boolean = runCatching { Path.of(text).isAbsolute }.getOrDefault(false)

/**
 * The settings to save: [draft]'s values where they differ from [saved] (what the user changed), over [fresh] (what the
 * engine has now). So a pause from the toolbar, an after-queue action that has fired, or a value the form can only show
 * rounded is not written back from a draft opened earlier.
 */
fun mergeEdits(
    fresh: TransferSettings,
    saved: TransferForm,
    draft: TransferForm,
): TransferSettings {
    val was = saved.toSettings()
    val now = draft.toSettings()

    fun <T> pick(field: (TransferSettings) -> T): T = if (field(now) != field(was)) field(now) else field(fresh)
    return TransferSettings(
        defaultSaveFolder = pick { it.defaultSaveFolder },
        clashPolicy = pick { it.clashPolicy },
        freeSpaceReserve = pick { it.freeSpaceReserve },
        playlistFile = pick { it.playlistFile },
        maxRetries = pick { it.maxRetries },
        historyLimit = pick { it.historyLimit },
        pacing = pick { it.pacing },
        connectionsPerDownload = pick { it.connectionsPerDownload },
        globalSpeedLimit = pick { it.globalSpeedLimit },
        globalUploadLimit = pick { it.globalUploadLimit },
        slowMode = pick { it.slowMode },
        slowModeLimit = pick { it.slowModeLimit },
        seedRatio = pick { it.seedRatio },
        seedMinutes = pick { it.seedMinutes },
        concurrencyLimit = pick { it.concurrencyLimit },
        globalPause = pick { it.globalPause },
        keepAwake = pick { it.keepAwake },
        afterQueue = pick { it.afterQueue },
    )
}

// The custom pacing typed, or none when it isn't valid; other presets keep it for when Custom is chosen again.
private fun TransferForm.customPacing(): PacingValues? =
    runCatching { PacingValues(pacingSleep.trim().toDouble(), pacingMin.trim().toDouble(), pacingMax.trim().toDouble()) }.getOrNull()

/** The settings the form says; call it only when [validate] found nothing. Quit becomes NONE: the app keeps it itself. */
fun TransferForm.toSettings(): TransferSettings =
    TransferSettings(
        defaultSaveFolder = folder.trim().ifEmpty { null },
        clashPolicy = clash,
        freeSpaceReserve = reserveMb.trim().toLong() * MB,
        playlistFile = playlistFile,
        maxRetries = retries.trim().toInt(),
        historyLimit = historyLimit.trim().toInt(),
        pacing = customPacing()?.let { Pacing(pacing, it) } ?: Pacing(pacing),
        connectionsPerDownload = connections.trim().toInt(),
        globalSpeedLimit =
            downKbps
                .trim()
                .ifEmpty { null }
                ?.toLong()
                ?.times(KB),
        globalUploadLimit =
            upKbps
                .trim()
                .ifEmpty { null }
                ?.toLong()
                ?.times(KB),
        slowMode = slowMode,
        slowModeLimit = slowKbps.trim().toLong() * KB,
        seedRatio = seedRatio.trim().ifEmpty { null }?.toDouble(),
        seedMinutes = seedMinutes.trim().ifEmpty { null }?.toInt(),
        concurrencyLimit = concurrency.trim().toInt(),
        globalPause = globalPause,
        keepAwake = keepAwake,
        afterQueue = afterQueue.action,
    )

enum class CategoryField { NAME, FOLDER, TEMPLATE }

/** A category as typed; the rule lists are comma-separated. */
data class CategoryForm(
    val name: String = "",
    val folder: String = "",
    val template: String = "",
    val extensions: String = "",
    val mimes: String = "",
    val domains: String = "",
    val kinds: Set<DownloadKind> = emptySet(),
)

fun categoryForm(category: Category): CategoryForm {
    fun <T : CategoryRule> rules(type: Class<T>) = category.rules.filterIsInstance(type)
    return CategoryForm(
        name = category.name,
        folder = category.folder,
        template = category.template.orEmpty(),
        extensions = rules(CategoryRule.Extension::class.java).flatMap { it.extensions }.joinToString(", "),
        mimes = rules(CategoryRule.Mime::class.java).flatMap { it.patterns }.joinToString(", "),
        domains = rules(CategoryRule.Domain::class.java).flatMap { it.domains }.joinToString(", "),
        kinds = rules(CategoryRule.Kind::class.java).flatMap { it.kinds }.toSet(),
    )
}

/** The fields a naming template may use, as they are typed. */
val templateFields: String = TemplateField.entries.joinToString(" ") { "{${it.token}}" }

private fun templateError(template: String): String? =
    when (val parsed = NameTemplate.parse(template)) {
        is TemplateParse.Ok -> null
        is TemplateParse.UnknownField -> "There is no field {${parsed.name}}. Fields: $templateFields"
    }

fun CategoryForm.validate(folderExists: (String) -> Boolean): Map<CategoryField, String> =
    buildMap {
        if (name.isBlank()) put(CategoryField.NAME, "Give it a name.")
        val folder = folder.trim()
        when {
            folder.isEmpty() || !isFullPath(folder) -> put(CategoryField.FOLDER, "Give a full path.")
            !folderExists(folder) -> put(CategoryField.FOLDER, "There is no such folder.")
        }
        if (template.isNotBlank()) templateError(template.trim())?.let { put(CategoryField.TEMPLATE, it) }
    }

private fun list(text: String): Set<String> =
    text
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

private fun CategoryForm.rules(): List<CategoryRule> =
    buildList {
        list(extensions)
            .map { it.removePrefix(".") }
            .toSet()
            .takeIf { it.isNotEmpty() }
            ?.let { add(CategoryRule.Extension(it)) }
        list(mimes).takeIf { it.isNotEmpty() }?.let { add(CategoryRule.Mime(it)) }
        list(domains).takeIf { it.isNotEmpty() }?.let { add(CategoryRule.Domain(it)) }
        if (kinds.isNotEmpty()) add(CategoryRule.Kind(kinds))
    }

/** Call these only when [validate] found nothing. */
fun CategoryForm.toNew(priority: Int): NewCategory =
    NewCategory(name.trim(), folder.trim(), template.trim().ifEmpty { null }, rules(), priority)

fun CategoryForm.toCategory(
    id: CategoryId,
    priority: Int,
): Category = Category(id, name.trim(), folder.trim(), template.trim().ifEmpty { null }, rules(), priority)

/** The media a profile asks for; ANY leaves it to the download. */
enum class ProfileMedia(
    val label: String,
    val height: Int? = null,
    val audio: AudioFormat? = null,
) {
    ANY("Any"),
    BEST("Best video"),
    P2160("2160p", height = 2160),
    P1080("1080p", height = 1080),
    P720("720p", height = 720),
    P480("480p", height = 480),
    MP3("Audio (mp3)", audio = AudioFormat.MP3),
    M4A("Audio (m4a)", audio = AudioFormat.M4A),
    OPUS("Audio (opus)", audio = AudioFormat.OPUS),
}

enum class ProfileField { NAME, FOLDER, TEMPLATE, SPEED }

data class ProfileForm(
    val name: String = "",
    val category: CategoryId? = null,
    val folder: String = "",
    val template: String = "",
    val speedKbps: String = "",
    val media: ProfileMedia = ProfileMedia.ANY,
    /** Which proxy its downloads go through; Inherit leaves it to the global choice. */
    val proxy: ProxyChoice = ProxyChoice.Inherit,
)

fun profileForm(profile: Profile): ProfileForm =
    ProfileForm(
        name = profile.name,
        category = profile.categoryId,
        folder = profile.saveFolder.orEmpty(),
        template = profile.nameTemplate.orEmpty(),
        speedKbps = profile.speedLimit?.let(::kbps).orEmpty(),
        proxy = profile.proxy,
        media =
            profile.format?.let { format ->
                ProfileMedia.entries.firstOrNull { it != ProfileMedia.ANY && it.height == format.height && it.audio == format.audioOnly }
            } ?: ProfileMedia.ANY,
    )

private const val MAX_PROFILE_NAME = 80

fun ProfileForm.validate(folderExists: (String) -> Boolean): Map<ProfileField, String> =
    buildMap {
        val name = name.trim()
        if (name.isEmpty() || name.length > MAX_PROFILE_NAME || name.any { it < ' ' || it == ' ' || it == ' ' }) {
            put(ProfileField.NAME, "1 to $MAX_PROFILE_NAME characters on one line.")
        }
        val folder = folder.trim()
        if (folder.isNotEmpty()) {
            when {
                !isFullPath(folder) -> put(ProfileField.FOLDER, "Give a full path, or leave it empty.")
                !folderExists(folder) -> put(ProfileField.FOLDER, "There is no such folder.")
            }
        }
        if (template.isNotBlank()) templateError(template.trim())?.let { put(ProfileField.TEMPLATE, it) }
        limit(speedKbps)?.let { put(ProfileField.SPEED, it) }
    }

// The form's media over [format]: what the form doesn't show (fps, subtitles...) is kept, except what audio-only can't have.
private fun ProfileForm.format(format: Format?): Format? {
    if (media == ProfileMedia.ANY) return null
    val base = format ?: Format()
    return if (media.audio != null) {
        base.copy(height = null, audioOnly = media.audio, subtitles = null, sponsorBlock = null)
    } else {
        base.copy(height = media.height, audioOnly = null)
    }
}

/** Call these only when [validate] found nothing. */
fun ProfileForm.toNew(): NewProfile =
    NewProfile(
        name.trim(),
        format(null),
        category,
        folder.trim().ifEmpty { null },
        template.trim().ifEmpty { null },
        speedKbps
            .trim()
            .ifEmpty { null }
            ?.toLong()
            ?.times(KB),
        proxy,
    )

/** [profile] with the form's values. */
fun ProfileForm.toProfile(profile: Profile): Profile =
    profile.copy(
        name = name.trim(),
        format = format(profile.format),
        categoryId = category,
        saveFolder = folder.trim().ifEmpty { null },
        nameTemplate = template.trim().ifEmpty { null },
        proxy = proxy,
        // A limit the form shows rounded (1500 B/s as 1 KB/s) stays as it was unless the text changed.
        speedLimit =
            if (speedKbps.trim() == profileForm(profile).speedKbps) {
                profile.speedLimit
            } else {
                speedKbps
                    .trim()
                    .ifEmpty { null }
                    ?.toLong()
                    ?.times(KB)
            },
    )
