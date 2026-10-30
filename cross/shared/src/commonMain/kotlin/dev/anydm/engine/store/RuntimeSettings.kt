package dev.anydm.engine.store

import dev.anydm.engine.tools.Tool
import dev.anydm.engine.tools.YtDlpChannel

/**
 * The settings of the OS, bridge, tool and diagnostics sections (#609), as the settings screen shows them: the ones
 * [Engine] had no way to read back. Folders are text, as the user typed them.
 */
data class RuntimeSettings(
    val notifications: Boolean = true,
    val bridgeOrigins: Set<String> = setOf(Settings.CHROME_EXTENSION_ORIGIN),
    val debugLogging: Boolean = false,
    val ytDlpChannel: YtDlpChannel = YtDlpChannel.STABLE,
    val systemTools: Boolean = true,
    val toolFolders: Map<Tool, String> = emptyMap(),
)

internal fun Settings.runtime() =
    RuntimeSettings(
        notifications = notifications,
        bridgeOrigins = bridgeOrigins,
        debugLogging = debugLogging,
        ytDlpChannel = ytDlpChannel,
        systemTools = systemTools,
        toolFolders = toolFolders.mapValues { it.value.toString() },
    )
