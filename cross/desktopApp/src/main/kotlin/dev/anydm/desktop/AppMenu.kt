package dev.anydm.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.WindowPlacement

private fun Shortcut.accelerator(platform: Platform): KeyShortcut =
    KeyShortcut(key, meta = platform == Platform.Mac, ctrl = platform != Platform.Mac, shift = shift)

/** The native menu bar, built from [menuModel] so it follows the same command table as the keys and the overlay. */
@Composable
fun FrameWindowScope.AppMenuBar(
    bus: CommandBus,
    platform: Platform,
) {
    val groups = menuModel(commandTable, bus.context)
    MenuBar {
        for (g in groups) {
            Menu(g.title) {
                for (e in g.entries) {
                    val shortcut = e.accelerator?.accelerator(platform)
                    if (e.checked != null) {
                        RadioButtonItem(
                            e.label,
                            selected = e.checked,
                            enabled = e.enabled,
                            shortcut = shortcut,
                            onClick = { bus.run(e.id) },
                        )
                    } else {
                        Item(e.label, enabled = e.enabled, shortcut = shortcut, onClick = { bus.run(e.id) })
                    }
                }
            }
        }
        if (hasWindowMenu(platform)) {
            Menu("Window") {
                Item("Minimize", onClick = { window.isMinimized = true })
                Item("Zoom", onClick = {
                    window.placement =
                        if (window.placement == WindowPlacement.Maximized) WindowPlacement.Floating else WindowPlacement.Maximized
                })
            }
        }
    }
}
