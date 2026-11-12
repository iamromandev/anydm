package dev.anydm.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType

enum class Platform {
    Mac,
    Other,
    ;

    companion object {
        fun current(): Platform =
            if (System
                    .getProperty("os.name")
                    .orEmpty()
                    .lowercase()
                    .startsWith("mac")
            ) {
                Mac
            } else {
                Other
            }
    }
}

enum class Group(
    val title: String,
) {
    File("File"),
    Edit("Edit"),
    View("View"),
}

enum class CommandId {
    NewDownload,
    OpenTorrent,
    Limits,
    SelectAll,
    ClearSelection,
    Pause,
    Resume,
    TogglePause,
    Remove,
    FilterAll,
    FilterDownloading,
    FilterFinished,
    ToggleTheme,
    ToggleUnit,
    SetUnitMBps,
    SetUnitMbps,
    Shortcuts,
}

data class Shortcut(
    val key: Key,
    val mod: Boolean = false,
    val shift: Boolean = false,
    val alt: Key? = null,
)

data class Command(
    val id: CommandId,
    val group: Group,
    val label: String,
    val shortcut: Shortcut?,
    val inMenu: Boolean = true,
    val inOverlay: Boolean = true,
)

val commandTable: List<Command> =
    listOf(
        Command(CommandId.NewDownload, Group.File, "New download", Shortcut(Key.N, mod = true)),
        Command(CommandId.OpenTorrent, Group.File, "Open .torrent…", Shortcut(Key.O, mod = true)),
        Command(CommandId.Limits, Group.File, "Speed & data limits…", Shortcut(Key.Comma, mod = true)),
        Command(CommandId.SelectAll, Group.Edit, "Select all", Shortcut(Key.A, mod = true)),
        Command(CommandId.ClearSelection, Group.Edit, "Clear selection", Shortcut(Key.Escape)),
        // Pause, Resume and TogglePause share Space: the key runs TogglePause, which picks one by the targets (see spaceAction).
        // The menu items carry no accelerator, because AWT menus cannot bind a bare key; the overlay still shows it.
        Command(CommandId.Pause, Group.Edit, "Pause", Shortcut(Key.Spacebar)),
        Command(CommandId.Resume, Group.Edit, "Resume", Shortcut(Key.Spacebar)),
        Command(CommandId.TogglePause, Group.Edit, "Pause or resume", Shortcut(Key.Spacebar), inMenu = false, inOverlay = false),
        Command(CommandId.Remove, Group.Edit, "Remove…", Shortcut(Key.Delete, alt = Key.Backspace)),
        Command(CommandId.FilterAll, Group.View, "All downloads", Shortcut(Key.One, mod = true)),
        Command(CommandId.FilterDownloading, Group.View, "Downloading", Shortcut(Key.Two, mod = true)),
        Command(CommandId.FilterFinished, Group.View, "Finished", Shortcut(Key.Three, mod = true)),
        Command(CommandId.ToggleTheme, Group.View, "Toggle theme", Shortcut(Key.D, mod = true, shift = true)),
        // The menu shows the unit as two radio items; the key (and the overlay) keep the single toggle.
        Command(CommandId.ToggleUnit, Group.View, "Switch speed unit", Shortcut(Key.U, mod = true, shift = true), inMenu = false),
        Command(CommandId.SetUnitMBps, Group.View, "MB/s", null, inOverlay = false),
        Command(CommandId.SetUnitMbps, Group.View, "Mbps", null, inOverlay = false),
        Command(CommandId.Shortcuts, Group.View, "Keyboard shortcuts", Shortcut(Key.Slash, mod = true)),
    )

data class OverlayEntry(
    val group: Group,
    val label: String,
    val shortcut: String,
)

data class CommandContext(
    val modalOpen: Boolean = false,
    val menuOpen: Boolean = false,
    val detailOpen: Boolean = false,
    val targets: List<DownloadItem> = emptyList(),
    val visibleCount: Int = 0,
    val hasSelection: Boolean = false,
    val filter: Filter = Filter.All,
    val unit: SpeedUnit = SpeedUnit.MBps,
    /** A text field in the Networks menu (rename or proxy) has the focus. */
    val editing: Boolean = false,
)

private val fileCommands = setOf(CommandId.NewDownload, CommandId.OpenTorrent, CommandId.Limits)

fun enabled(
    id: CommandId,
    ctx: CommandContext,
): Boolean {
    if (ctx.modalOpen) return id == CommandId.Shortcuts
    if (id in fileCommands) return !ctx.editing
    if (ctx.menuOpen) return false
    return when (id) {
        CommandId.SelectAll -> !ctx.detailOpen && ctx.visibleCount > 0
        CommandId.ClearSelection -> !ctx.detailOpen && ctx.hasSelection
        CommandId.Pause -> !ctx.detailOpen && canPause(ctx.targets)
        CommandId.Resume -> !ctx.detailOpen && canResume(ctx.targets)
        CommandId.TogglePause -> spaceAction(ctx.targets) != null
        CommandId.Remove -> ctx.targets.isNotEmpty()
        else -> true
    }
}

/** Whether a menu item shows a check mark; null when the command is not checkable. */
fun checked(
    id: CommandId,
    ctx: CommandContext,
): Boolean? =
    when (id) {
        CommandId.FilterAll -> ctx.filter == Filter.All
        CommandId.FilterDownloading -> ctx.filter == Filter.Downloading
        CommandId.FilterFinished -> ctx.filter == Filter.Finished
        CommandId.SetUnitMbps -> ctx.unit == SpeedUnit.Mbps
        CommandId.SetUnitMBps -> ctx.unit == SpeedUnit.MBps
        else -> null
    }

/** Commands bound to Mod plus a key. The platform's own modifier must be down and the other one up. */
fun matchKey(
    key: Key,
    meta: Boolean,
    ctrl: Boolean,
    shift: Boolean,
    platform: Platform,
    alt: Boolean = false,
): CommandId? {
    val mod = if (platform == Platform.Mac) meta && !ctrl else ctrl && !meta
    if (!mod || alt) return null
    return commandTable
        .firstOrNull {
            val s = it.shortcut
            s != null && s.mod && s.key == key && s.shift == shift
        }?.id
}

/** Bare keys: Space, Delete or Backspace, Esc, with no modifier down. Space maps to TogglePause, not to Pause or Resume. */
fun matchBareKey(
    key: Key,
    shift: Boolean = false,
    alt: Boolean = false,
    meta: Boolean = false,
    ctrl: Boolean = false,
): CommandId? =
    if (shift || alt || meta || ctrl) {
        null
    } else {
        commandTable
            .firstOrNull {
                val s = it.shortcut
                s != null && !s.mod && it.id != CommandId.Pause && it.id != CommandId.Resume && (s.key == key || s.alt == key)
            }?.id
    }

private fun keyName(key: Key): String =
    when (key) {
        Key.Comma -> ","
        Key.Slash -> "/"
        Key.One -> "1"
        Key.Two -> "2"
        Key.Three -> "3"
        Key.Spacebar -> "Space"
        Key.Escape -> "Esc"
        Key.Delete -> "Delete"
        Key.Backspace -> "Backspace"
        else -> key.toString().substringAfterLast(' ').removeSuffix(")")
    }

fun shortcutLabel(
    s: Shortcut,
    platform: Platform,
): String =
    if (platform == Platform.Mac) {
        val key = if (s.alt == Key.Backspace) "⌫" else keyName(s.key)
        (if (s.shift) "⇧" else "") + (if (s.mod) "⌘" else "") + key
    } else {
        listOfNotNull("Ctrl".takeIf { s.mod }, "Shift".takeIf { s.shift }, keyName(s.key)).joinToString("+")
    }

/** The rows of the shortcuts overlay: every command that has a place in it, in table order. */
fun overlayEntries(platform: Platform): List<OverlayEntry> =
    commandTable.filter { it.inOverlay }.map {
        OverlayEntry(
            it.group,
            it.label,
            it.shortcut
                ?.let { s ->
                    shortcutLabel(s, platform)
                }.orEmpty(),
        )
    }

data class MenuEntry(
    val id: CommandId,
    val label: String,
    val enabled: Boolean,
    /** Null for a plain item, else the state of a radio item. */
    val checked: Boolean?,
    /** Only Mod shortcuts: AWT menus cannot bind a bare key. */
    val accelerator: Shortcut?,
)

data class MenuGroup(
    val title: String,
    val entries: List<MenuEntry>,
)

/** The native menu as data: groups in order, each entry with its enabled, checked and accelerator state. */
fun menuModel(
    table: List<Command>,
    ctx: CommandContext,
): List<MenuGroup> =
    Group.entries.map { g ->
        MenuGroup(
            g.title,
            table.filter { it.group == g && it.inMenu }.map {
                MenuEntry(it.id, it.label, enabled(it.id, ctx), checked(it.id, ctx), it.shortcut?.takeIf { s -> s.mod })
            },
        )
    }

/** Whether a key press that matched [id] is taken by the app, so a text field does not also see it. */
fun consumes(
    id: CommandId,
    ctx: CommandContext,
): Boolean = enabled(id, ctx) || (ctx.modalOpen && (id in fileCommands || id == CommandId.Shortcuts))

fun hasWindowMenu(platform: Platform): Boolean = platform == Platform.Mac

/** What Space does to these targets: pause when any is Downloading or Queued (also for a mix), else resume when any is Paused. */
fun spaceAction(targets: List<DownloadItem>): CommandId? =
    when {
        canPause(targets) -> CommandId.Pause
        canResume(targets) -> CommandId.Resume
        else -> null
    }

/**
 * Every command goes through here, from the menu or from a key. A command that is disabled does not run.
 * A second run of the same id within [DEDUPE_MS] is dropped, so a menu accelerator and a window key event for one press run once.
 */
class CommandBus(
    private val now: () -> Long = System::currentTimeMillis,
) {
    var context by mutableStateOf(CommandContext())
    var handler: (CommandId) -> Unit = {}

    /** Reads the state as it is now; [context] is only as fresh as the last composition. */
    var contextSource: (() -> CommandContext)? = null
    val current: CommandContext get() = contextSource?.invoke() ?: context

    /** A key was released: toggles may run again. */
    fun release() {
        toggleAttempt.clear()
    }

    private val last = mutableMapOf<CommandId, Long>()
    private val toggleAttempt = mutableMapOf<CommandId, Long>()

    fun run(
        id: CommandId,
        dedupe: Boolean = true,
    ): Boolean {
        if (!enabled(id, current)) return false
        if (id in toggles) {
            // A held accelerator repeats natively, and the menu cannot tell a repeat from a press: each attempt extends the lockout.
            val t = now()
            val before = toggleAttempt[id]
            toggleAttempt[id] = t
            if (before != null && t - before < TOGGLE_LOCKOUT_MS) return false
        }
        if (dedupe) {
            val t = now()
            val before = last[id]
            if (before != null && t - before < DEDUPE_MS) return false
            last[id] = t
        }
        handler(id)
        return true
    }
}

const val DEDUPE_MS = 150L

/** A toggle runs again only after a key was released or this long without a repeat. */
const val TOGGLE_LOCKOUT_MS = 500L

private val toggles = setOf(CommandId.ToggleTheme, CommandId.ToggleUnit)

private val commandKeys = setOf(Key.MetaLeft, Key.MetaRight, Key.CtrlLeft, Key.CtrlRight)

/** Which keys are down, so that a held key counts once: AWT sends a KeyDown for every auto-repeat. */
internal class KeyTracker(
    private val onRelease: () -> Unit = {},
) {
    private val held = mutableSetOf<Key>()
    var repeating = false
        private set

    fun track(
        key: Key,
        type: KeyEventType,
    ) {
        if (type == KeyEventType.KeyDown) {
            repeating = !held.add(key)
        } else if (type == KeyEventType.KeyUp) {
            // macOS sends no KeyUp for a letter pressed with Command, but it does for Command itself.
            if (key in commandKeys) {
                clear()
            } else {
                held.remove(key)
                onRelease()
            }
        }
    }

    /** Forgets every key: the window lost the focus or a dialog took the keys, so KeyUps may be missing. */
    fun clear() {
        held.clear()
        repeating = false
        onRelease()
    }
}
