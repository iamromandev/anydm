package dev.anydm.desktop

/** What a tray menu entry does. */
enum class TrayAction { TOGGLE_WINDOW, OPEN, TOGGLE_PAUSE, QUIT }

data class TrayItem(
    val action: TrayAction,
    val label: String,
    val enabled: Boolean = true,
)

/** The tray menu (#610): show or hide the window, open it on the downloads, pause or resume everything, quit. */
fun trayMenu(
    window: WindowState,
    paused: Boolean,
): List<TrayItem> =
    listOf(
        TrayItem(TrayAction.TOGGLE_WINDOW, if (window.visible) "Hide anydm" else "Show anydm"),
        TrayItem(TrayAction.OPEN, "Open main window"),
        TrayItem(TrayAction.TOGGLE_PAUSE, if (paused) "Resume all" else "Pause all"),
        TrayItem(TrayAction.QUIT, "Quit"),
    )

/** The tray icon's tooltip: the name, and what is going on. */
fun trayTooltip(state: UiState): String {
    val active = state.counts[StatusGroup.ACTIVE]
    val parts =
        listOfNotNull(
            "$active downloading".takeIf { active > 0 },
            formatSpeed(state.speed).takeIf { state.speed > 0 },
            "paused".takeIf { state.globallyPaused },
        )
    return (listOf("anydm") + parts).joinToString(" · ")
}
