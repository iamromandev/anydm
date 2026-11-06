package dev.anydm.desktop

import java.util.prefs.Preferences

/** Which colors the app wears; dark is the default. */
enum class ThemeChoice { DARK, LIGHT }

/** Whether speeds read in megabytes or megabits per second. */
enum class SpeedUnit { BYTES, BITS }

/** What the user chose about how the app looks; kept between runs. */
interface ViewPrefs {
    var theme: ThemeChoice
    var speedUnit: SpeedUnit

    /** Whether a newer release is downloaded as soon as it is found; on by default. */
    var autoUpdate: Boolean
}

/** The choices in the user's preferences store (the registry, a plist, or a file under `~/.java`). */
class PreferencesViewPrefs(
    private val node: Preferences = Preferences.userRoot().node("dev/anydm/desktop"),
) : ViewPrefs {
    override var theme: ThemeChoice
        get() = read(THEME, ThemeChoice.DARK, ThemeChoice::valueOf)
        set(value) = write(THEME, value.name)

    override var speedUnit: SpeedUnit
        get() = read(SPEED_UNIT, SpeedUnit.BYTES, SpeedUnit::valueOf)
        set(value) = write(SPEED_UNIT, value.name)

    override var autoUpdate: Boolean
        get() = read(AUTO_UPDATE, true) { it.toStrictBoolean() }
        set(value) = write(AUTO_UPDATE, value.toString())

    private fun String.toStrictBoolean(): Boolean =
        when (this) {
            "true" -> true
            "false" -> false
            else -> error("not a boolean")
        }

    private fun <T> read(
        key: String,
        default: T,
        parse: (String) -> T,
    ): T = runCatching { parse(node.get(key, null) ?: return default) }.getOrDefault(default)

    // A store that can't be written costs the choice, not the app.
    private fun write(
        key: String,
        value: String,
    ) {
        runCatching {
            node.put(key, value)
            node.flush()
        }
    }

    private companion object {
        const val THEME = "theme"
        const val SPEED_UNIT = "speedUnit"
        const val AUTO_UPDATE = "autoUpdate"
    }
}

/** A [ViewPrefs] that keeps its choices in memory only: for tests. */
class MemoryViewPrefs(
    override var theme: ThemeChoice = ThemeChoice.DARK,
    override var speedUnit: SpeedUnit = SpeedUnit.BYTES,
    override var autoUpdate: Boolean = true,
) : ViewPrefs
