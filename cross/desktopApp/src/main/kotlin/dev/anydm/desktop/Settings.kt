package dev.anydm.desktop

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.time.DayOfWeek

/** The theme the user picked. System follows the OS setting; the footer toggle only ever sets Light or Dark. */
enum class ThemeChoice {
    System,
    Light,
    Dark,
}

/** The sections of the Settings page, in sidebar order. */
enum class SettingsSection(
    val label: String,
    val tag: String,
) {
    General("General", "general"),
    Appearance("Appearance", "appearance"),
    SpeedData("Speed & data", "speeddata"),
    Networks("Networks", "networks"),
}

fun resolveDark(
    choice: ThemeChoice,
    systemDark: Boolean,
): Boolean =
    when (choice) {
        ThemeChoice.System -> systemDark
        ThemeChoice.Light -> false
        ThemeChoice.Dark -> true
    }

const val SETTINGS_VERSION = 1
const val SETTINGS_FILE_NAME = "settings.json"
private const val APP_DIR_NAME = "anydm"

/** Tolerates fields a later release may add within the same version. A password has no field here, so none can be written. */
val settingsJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

@Serializable
data class SettingsFile(
    val version: Int = SETTINGS_VERSION,
    val theme: ThemeChoice = ThemeChoice.System,
    val unit: SpeedUnit = SpeedUnit.MBps,
    val saveFolder: String? = null,
    val limits: LimitsDto = LimitsDto(),
    val networks: List<NetworkDto> = emptyList(),
)

/** Usage bytes are sample data and are not persisted. */
@Serializable
data class LimitsDto(
    val total: Long? = null,
    val slowOn: Boolean = false,
    val slow: Long = initialLimits(emptyList(), emptyMap()).slow,
    val concurrency: Int = DEFAULT_CONCURRENCY,
    val schedule: ScheduleDto = ScheduleDto(),
    val networks: Map<String, NetworkLimitsDto> = emptyMap(),
)

@Serializable
data class ScheduleDto(
    val enabled: Boolean = DEFAULT_SCHEDULE.enabled,
    val days: List<String> = daysToNames(DEFAULT_SCHEDULE.days),
    val start: String = DEFAULT_SCHEDULE.start,
    val end: String = DEFAULT_SCHEDULE.end,
)

@Serializable
data class NetworkLimitsDto(
    val speed: Long? = null,
    val data: Long? = null,
    val period: DataPeriod = DataPeriod.Month,
)

@Serializable
data class NetworkDto(
    val id: String,
    val name: String,
    val color: NetworkColor? = null,
    val proxy: ProxyDto? = null,
)

/** Never a password: that goes to the OS keychain only. */
@Serializable
data class ProxyDto(
    val type: ProxyType,
    val host: String = "",
    val port: Int = 0,
    val user: String = "",
)

/** What a settings file puts back into the app. Proxy passwords are always empty here. */
data class Restored(
    val theme: ThemeChoice,
    val unit: SpeedUnit,
    val saveFolder: String?,
    val limits: Limits,
    val networks: List<Network>,
)

private fun daysToNames(days: Set<DayOfWeek>) = days.sorted().map { it.name }

fun toFile(
    theme: ThemeChoice,
    unit: SpeedUnit,
    saveFolder: String?,
    limits: Limits,
    networks: List<Network>,
): SettingsFile =
    SettingsFile(
        SETTINGS_VERSION,
        theme,
        unit,
        saveFolder,
        LimitsDto(
            limits.total,
            limits.slowOn,
            limits.slow,
            limits.concurrency,
            ScheduleDto(limits.schedule.enabled, daysToNames(limits.schedule.days), limits.schedule.start, limits.schedule.end),
            limits.networks.mapValues { (_, n) -> NetworkLimitsDto(n.speed, n.data, n.period) },
        ),
        networks.map { n ->
            val p = n.proxy
            NetworkDto(n.id, n.name, n.color, if (p.type == ProxyType.None) null else ProxyDto(p.type, p.host, p.port, p.user))
        },
    )

/** Merges by network id (R10): file entries for unknown ids are dropped, and networks missing from the file keep the app's values. */
fun fromFile(
    f: SettingsFile,
    appNetworks: List<Network>,
    defaults: Limits,
): Restored {
    val byId = f.networks.associateBy { it.id }
    val networks =
        appNetworks.map { n ->
            val d = byId[n.id] ?: return@map n
            n.copy(
                name = d.name,
                color = d.color,
                proxy = d.proxy?.let { Proxy(it.type, it.host, it.port, it.user) } ?: Proxy.NONE,
            )
        }
    val limits = f.limits
    return Restored(
        f.theme,
        f.unit,
        f.saveFolder,
        Limits(
            limits.total,
            limits.slowOn,
            limits.slow,
            limits.concurrency,
            Schedule(
                limits.schedule.enabled,
                limits.schedule.days.mapTo(mutableSetOf()) {
                    DayOfWeek.valueOf(it)
                },
                limits.schedule.start,
                limits.schedule.end,
            ),
            appNetworks.associate { n ->
                val own = defaults.networks[n.id] ?: NetworkLimits(null, null, DataPeriod.Month, 0)
                val d = limits.networks[n.id]
                // Usage is sample data, so it always comes from the app, never from the file.
                n.id to if (d == null) own else NetworkLimits(d.speed, d.data, d.period, own.used)
            },
        ),
        networks,
    )
}

private const val MAX_SETTINGS_TEXT = 4096
private const val MAX_BYTES_SPEED = (MAX_LIMIT * 1024 * 1024).toLong()
private const val MAX_BYTES_DATA = (MAX_LIMIT * 1024 * 1024 * 1024).toLong()

private fun amountProblem(
    label: String,
    value: Long?,
    max: Long,
): String? = if (value != null && (value <= 0 || value > max)) "$label out of range" else null

private fun proxyProblem(p: ProxyDto): String? =
    when {
        p.type == ProxyType.None -> null
        !isHost(p.host) -> "proxy host is not a host name"
        p.port !in 1..65535 -> "proxy port out of range"
        p.user.length > MAX_SETTINGS_TEXT -> "proxy user too long"
        else -> null
    }

/** A saved folder must be an absolute path of this system: a relative one would depend on where the app was started. */
internal fun isUsableFolder(folder: String): Boolean =
    folder.isNotBlank() &&
        folder.length <= MAX_SETTINGS_TEXT &&
        try {
            Path.of(folder).isAbsolute
        } catch (_: java.nio.file.InvalidPathException) {
            false
        }

/** Null when the file is usable, else the first reason it is not. One bad value makes the whole file invalid. */
fun validate(f: SettingsFile): String? {
    val l = f.limits
    val schedule = l.schedule
    val days = schedule.days.map { name -> DayOfWeek.entries.firstOrNull { it.name == name } }
    val ids = f.networks.map { it.id }
    val speedProblem = amountProblem("total speed", l.total, MAX_BYTES_SPEED) ?: amountProblem("slow speed", l.slow, MAX_BYTES_SPEED)
    return when {
        f.version > SETTINGS_VERSION -> {
            "newer settings version ${f.version}"
        }

        f.version < SETTINGS_VERSION -> {
            "unknown settings version ${f.version}"
        }

        f.saveFolder != null && !isUsableFolder(f.saveFolder) -> {
            "bad save folder"
        }

        l.concurrency !in MIN_CONCURRENCY..MAX_CONCURRENCY -> {
            "downloads at once out of range"
        }

        speedProblem != null -> {
            speedProblem
        }

        days.any { it == null } -> {
            "unknown day in schedule"
        }

        schedule.start.length > MAX_SETTINGS_TEXT || schedule.end.length > MAX_SETTINGS_TEXT -> {
            "bad schedule time"
        }

        scheduleError(Schedule(schedule.enabled, days.filterNotNull().toSet(), schedule.start, schedule.end)) != null -> {
            "bad schedule"
        }

        ids.any { it.isBlank() || it.length > MAX_SETTINGS_TEXT } -> {
            "bad network id"
        }

        ids.toSet().size != ids.size -> {
            "duplicate network id"
        }

        f.networks.any { it.name.isBlank() || it.name.length > MAX_NETWORK_NAME_LENGTH } -> {
            "bad network name"
        }

        else -> {
            f.networks.firstNotNullOfOrNull { n -> n.proxy?.let { proxyProblem(it) } }
                ?: l.networks.values.firstNotNullOfOrNull { n ->
                    amountProblem("network speed", n.speed, MAX_BYTES_SPEED) ?: amountProblem("network data", n.data, MAX_BYTES_DATA)
                }
        }
    }
}

/** The folder of `settings.json`. Pure: the caller passes the environment, so tests never see the real one. */
fun appDataDir(
    platform: Platform,
    env: Map<String, String>,
    home: String,
    os: String,
): Path =
    when {
        platform == Platform.Mac -> {
            Path.of(home, "Library", "Application Support", APP_DIR_NAME)
        }

        os.lowercase().startsWith("windows") -> {
            (env["APPDATA"]?.takeIf { it.isNotBlank() }?.let { Path.of(it) } ?: Path.of(home, "AppData", "Roaming")).resolve(APP_DIR_NAME)
        }

        else -> {
            val xdg = env["XDG_CONFIG_HOME"]?.takeIf { it.isNotBlank() && Path.of(it).isAbsolute }
            (xdg?.let { Path.of(it) } ?: Path.of(home, ".config")).resolve(APP_DIR_NAME)
        }
    }
