package dev.anydm.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.prefs.Preferences

/** Which colors the app wears: the OS's, or always light or dark. */
enum class ThemeChoice { SYSTEM, LIGHT, DARK }

/** What the user chose about the window (#610); kept between runs. */
interface AppPrefs {
    /** Closing the window hides it to the tray instead of quitting. */
    var closeToTray: Boolean

    /** The app starts with the window hidden. */
    var startHidden: Boolean

    /** The color scheme; [ThemeChoice.SYSTEM] when none was chosen. */
    var theme: ThemeChoice

    /** The networks strip above the downloads shows only its header. */
    var stripCollapsed: Boolean
}

/** The choices in the user's preferences store (the registry, a plist, or a file under `~/.java`). */
class PreferencesAppPrefs(
    private val node: Preferences = Preferences.userRoot().node("dev/anydm/desktop"),
) : AppPrefs {
    override var closeToTray: Boolean
        get() = runCatching { node.getBoolean(CLOSE_TO_TRAY, true) }.getOrDefault(true)
        set(value) = write(CLOSE_TO_TRAY, value)

    override var startHidden: Boolean
        get() = runCatching { node.getBoolean(START_HIDDEN, false) }.getOrDefault(false)
        set(value) = write(START_HIDDEN, value)

    override var theme: ThemeChoice
        get() =
            runCatching { ThemeChoice.valueOf(node.get(THEME, ThemeChoice.SYSTEM.name)) }.getOrDefault(ThemeChoice.SYSTEM)
        set(value) = write(THEME, value.name)

    override var stripCollapsed: Boolean
        get() = runCatching { node.getBoolean(STRIP_COLLAPSED, false) }.getOrDefault(false)
        set(value) = write(STRIP_COLLAPSED, value)

    // A store that can't be written costs the choice, not the app.
    private fun write(
        key: String,
        value: Boolean,
    ) = write(key) { node.putBoolean(key, value) }

    private fun write(
        key: String,
        value: String,
    ) = write(key) { node.put(key, value) }

    private fun write(
        key: String,
        put: () -> Unit,
    ) {
        runCatching {
            put()
            node.flush()
        }
    }

    private companion object {
        const val CLOSE_TO_TRAY = "closeToTray"
        const val START_HIDDEN = "startHidden"
        const val THEME = "theme"
        const val STRIP_COLLAPSED = "stripCollapsed"
    }
}

/** What closing the window does. */
enum class CloseAction { HIDE, QUIT }

data class WindowState(
    val visible: Boolean = true,
    val closeToTray: Boolean = true,
    val startHidden: Boolean = false,
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    val stripCollapsed: Boolean = false,
)

/**
 * Whether the window is shown, and what closing it does (#610). Hiding is only for an app with a tray to bring it back from:
 * without one, closing quits and a hidden start is ignored, so the window can never be lost.
 */
class WindowModel(
    private val prefs: AppPrefs,
    val trayAvailable: Boolean,
) {
    private val ui =
        MutableStateFlow(
            WindowState(
                closeToTray = prefs.closeToTray,
                startHidden = prefs.startHidden,
                theme = prefs.theme,
                stripCollapsed = prefs.stripCollapsed,
            ),
        )
    val state: StateFlow<WindowState> = ui.asStateFlow()

    fun show() = ui.update { it.copy(visible = true) }

    fun hide() = ui.update { it.copy(visible = false) }

    fun toggle() = ui.update { it.copy(visible = !it.visible) }

    fun setCloseToTray(on: Boolean) {
        prefs.closeToTray = on
        ui.update { it.copy(closeToTray = on) }
    }

    fun setStartHidden(on: Boolean) {
        prefs.startHidden = on
        ui.update { it.copy(startHidden = on) }
    }

    fun setTheme(choice: ThemeChoice) {
        prefs.theme = choice
        ui.update { it.copy(theme = choice) }
    }

    fun setStripCollapsed(on: Boolean) {
        prefs.stripCollapsed = on
        ui.update { it.copy(stripCollapsed = on) }
    }

    /** The window's close button: hides it to the tray, or says to quit. */
    fun close(): CloseAction {
        if (!trayAvailable || !ui.value.closeToTray) return CloseAction.QUIT
        hide()
        return CloseAction.HIDE
    }

    /** The first launch: shown unless a hidden start was asked for (the flag, or the choice) and a tray can bring it back. A link shows it. */
    fun startUp(
        hiddenRequested: Boolean,
        hasLinks: Boolean,
    ) = ui.update { it.copy(visible = hasLinks || !(trayAvailable && (hiddenRequested || it.startHidden))) }
}
