package dev.anydm.desktop

import dev.anydm.engine.net.NetworkInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** The longest name a person can give a network. */
const val MAX_NETWORK_NAME = 40

/** The eight colors a network can be given, in the order the theme lists them. */
internal val SWATCH_IDS = listOf("teal", "amber", "steel", "rose", "violet", "lime", "cyan", "coral")

/** What kind of link a network is, as far as its name tells; the engine does not say. [hue] is the swatch its kind starts from. */
enum class NetworkKind(
    val tag: String,
    val hue: String?,
) {
    WIFI("WIFI", "teal"),
    USB("USB", "amber"),
    ETHERNET("ETH", "steel"),
    OTHER("NET", null),
}

private val WIFI_KEY = Regex("^(wl\\w*|en0)$")
private val ETHERNET_KEY = Regex("^(eth\\d*|en[ops]\\w*)$")

/**
 * The kind of [info], guessed from its interface name and the name the OS gave it (`wlan0`, `enx…`, "Intel(R) Wi-Fi 6",
 * "Remote NDIS"). A tethered phone is told by its USB or RNDIS name; macOS names carry no kind, so `en0` (the built-in Wi-Fi)
 * is Wi-Fi and its other `enN` are no kind at all.
 */
fun kindOf(info: NetworkInfo): NetworkKind {
    val key = info.key.lowercase()
    val name = info.name.lowercase()

    fun any(vararg words: String) = words.any { it in name || it in key }
    return when {
        any("usb", "ndis", "tether", "iphone", "ipheth", "android") || key.startsWith("enx") -> NetworkKind.USB
        any("wi-fi", "wifi", "wireless", "wlan") || WIFI_KEY.matches(key) -> NetworkKind.WIFI
        any("ethernet", "gbe", "gigabit") || ETHERNET_KEY.matches(key) -> NetworkKind.ETHERNET
        else -> NetworkKind.OTHER
    }
}

/** The answer to "is this a name I can keep". */
sealed interface NameCheck {
    data class Ok(
        val name: String,
    ) : NameCheck

    data object Empty : NameCheck

    data object TooLong : NameCheck
}

/** [raw] trimmed; it must hold something and be at most [MAX_NETWORK_NAME] characters. */
fun checkNetworkName(raw: String): NameCheck {
    val name = raw.trim()
    return when {
        name.isEmpty() -> NameCheck.Empty
        name.length > MAX_NETWORK_NAME -> NameCheck.TooLong
        else -> NameCheck.Ok(name)
    }
}

/** What a person chose for a network: a name, a swatch id, either or both. */
data class NetworkPref(
    val name: String? = null,
    val color: String? = null,
)

sealed interface VisualsResult {
    data object Saved : VisualsResult

    /** The name or color was refused; nothing changed. */
    data class Invalid(
        val reason: String,
    ) : VisualsResult

    /** The change holds for this run but the file could not be written. */
    data class NotSaved(
        val reason: String,
    ) : VisualsResult
}

/**
 * The names and colors a person gave to networks, kept in a JSON file ([file]) beside the engine's data, keyed by network
 * id: `{"en7": {"name": "Pixel", "color": "rose"}}`. Null [file] keeps them in memory only. The engine's settings are a typed
 * record with no room for these, so they live here. A file that is missing, broken or holds entries that break the rules gives
 * what is good in it and nothing worse.
 */
class NetworkVisuals(
    private val file: Path?,
) {
    private val lock = Any()

    // The file was there but not readable: the first write keeps a copy of it before it is replaced.
    private var damaged = false

    // What the file held when it was read, kept for the backup so a retry saves that, not a file this class already rewrote.
    private var original: ByteArray? = null
    private val state = MutableStateFlow(load())
    val prefs: StateFlow<Map<String, NetworkPref>> = state.asStateFlow()

    fun rename(
        key: String,
        raw: String,
    ): VisualsResult =
        when (val check = checkNetworkName(raw)) {
            is NameCheck.Ok -> change(key) { it.copy(name = check.name) }
            NameCheck.Empty -> VisualsResult.Invalid("A network needs a name.")
            NameCheck.TooLong -> VisualsResult.Invalid("A name can have at most $MAX_NETWORK_NAME characters.")
        }

    fun pick(
        key: String,
        colorId: String,
    ): VisualsResult {
        if (colorId !in SWATCH_IDS) return VisualsResult.Invalid("Not a color: $colorId")
        return change(key) { it.copy(color = colorId) }
    }

    private fun change(
        key: String,
        edit: (NetworkPref) -> NetworkPref,
    ): VisualsResult =
        synchronized(lock) {
            val next = state.value + (key to edit(state.value[key] ?: NetworkPref()))
            state.value = next
            write(next)
        }

    private fun load(): Map<String, NetworkPref> {
        val path = file ?: return emptyMap()
        val root =
            try {
                if (!Files.isRegularFile(path)) return emptyMap()
                original = runCatching { Files.readAllBytes(path) }.getOrNull()
                Json.parseToJsonElement(Files.readString(path)).jsonObject
            } catch (_: Exception) {
                damaged = true
                return emptyMap()
            }
        val loaded = linkedMapOf<String, NetworkPref>()
        for ((key, entry) in root) {
            val fields = entry as? JsonObject
            if (fields == null) {
                damaged = true
                continue
            }
            val rawName = fields.text("name")
            val rawColor = fields.text("color")
            val name = (checkNetworkName(rawName ?: "") as? NameCheck.Ok)?.name
            val color = rawColor?.takeIf { it in SWATCH_IDS }
            // Whatever is dropped is kept in a backup before the file is first rewritten.
            if ((fields["name"] != null && name == null) || (fields["color"] != null && color == null)) damaged = true
            if (name != null || color != null) loaded[key] = NetworkPref(name, color) else damaged = true
        }
        return loaded
    }

    private fun JsonObject.text(field: String): String? =
        (this[field] as? JsonPrimitive)?.takeIf { it.isString }?.jsonPrimitive?.contentOrNull

    private fun write(prefs: Map<String, NetworkPref>): VisualsResult {
        val path = file ?: return VisualsResult.Saved
        val json =
            buildJsonObject {
                for ((key, pref) in prefs.toSortedMap()) {
                    put(
                        key,
                        buildJsonObject {
                            pref.name?.let { put("name", it) }
                            pref.color?.let { put("color", it) }
                        },
                    )
                }
            }
        return try {
            Files.createDirectories(path.toAbsolutePath().parent)
            if (damaged) {
                // What a person set up once is not lost to one bad byte: the broken file is kept beside the new one.
                // Only a copy that was made lets go of the worry: a failed one is tried again at the next write.
                val backup = path.resolveSibling("${path.fileName}.bak")
                damaged =
                    runCatching {
                        val bytes = original
                        if (bytes != null) Files.write(backup, bytes) else Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING)
                    }.isFailure
            }
            // Written whole beside it and moved over it: a crash leaves the old file, not half of a new one.
            val temp = path.resolveSibling("${path.fileName}.tmp")
            Files.writeString(temp, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), json))
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
            }
            VisualsResult.Saved
        } catch (e: java.io.IOException) {
            VisualsResult.NotSaved("The change holds until anydm quits; it could not be saved: ${e.message ?: e::class.simpleName}")
        }
    }
}

/**
 * A swatch id for every network in [kinds]: a color a person pinned always wins (a pin that names no swatch is no pin); then
 * each network takes the hue of its kind if nobody has it; then each takes the first swatch nobody has, and once all eight are
 * taken, its kind's hue or else the first. Networks are taken in key order, so the answer doesn't depend on the listing's.
 */
fun assignSwatchIds(
    kinds: Map<String, NetworkKind>,
    pinned: Map<String, String>,
): Map<String, String> {
    val keys = kinds.keys.sorted()
    val picked = linkedMapOf<String, String>()
    val used = mutableSetOf<String>()

    fun take(
        key: String,
        id: String,
    ) {
        picked[key] = id
        used += id
    }
    for (key in keys) pinned[key]?.takeIf { it in SWATCH_IDS }?.let { take(key, it) }
    for (key in keys) {
        val hue = kinds.getValue(key).hue
        if (key !in picked && hue != null && hue !in used) take(key, hue)
    }
    for (key in keys) {
        if (key in picked) continue
        take(key, SWATCH_IDS.firstOrNull { it !in used } ?: kinds.getValue(key).hue ?: SWATCH_IDS.first())
    }
    return keys.associateWith { picked.getValue(it) }
}

/** [assignSwatchIds] as the theme's swatches. */
fun assignSwatches(
    kinds: Map<String, NetworkKind>,
    pinned: Map<String, String>,
    colors: PlexoColors,
): Map<String, Swatch> {
    val byId = colors.swatches.associateBy { it.id }
    return assignSwatchIds(kinds, pinned).mapValues { byId.getValue(it.value) }
}
