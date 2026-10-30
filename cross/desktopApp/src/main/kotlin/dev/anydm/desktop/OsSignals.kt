package dev.anydm.desktop

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.net.AfterQueueAction
import dev.anydm.engine.net.AfterQueueState
import dev.anydm.engine.os.ClipboardLink
import dev.anydm.engine.os.HotkeyStatus
import kotlin.time.Clock
import kotlin.time.Instant

/** What the OS services tell the app (#610), as the window shows it: indicators, notices and the clipboard offer. */
data class OsSignals(
    /** A link the user copied, offered for review until accepted or dismissed. */
    val clipboard: ClipboardLink? = null,
    val hotkeyOn: Boolean = false,
    val hotkey: HotkeyStatus = HotkeyStatus.Off,
    /** The hotkey status whose notice the user closed; the notice returns when the status changes. */
    val hotkeyDismissed: HotkeyStatus? = null,
    val afterQueue: AfterQueueState = AfterQueueState.Idle,
    val keepAwake: Boolean = true,
    val bridge: BridgeStatus = BridgeStatus.Off,
    /** The last download a notification was about, for a click on the tray to open. */
    val lastNotified: DownloadId? = null,
)

/** A line in the status bar. */
data class Indicator(
    val tag: String,
    val text: String,
)

/** Why the global hotkey does not work yet, and what to do. */
data class Notice(
    val text: String,
)

/** The notice for a hotkey that is on and can't work: permission, a system that can't do it, or a failure. Null when all is well or it is off. */
fun hotkeyNotice(os: OsSignals): Notice? {
    if (!os.hotkeyOn || os.hotkeyDismissed == os.hotkey) return null
    return when (val status = os.hotkey) {
        HotkeyStatus.NeedsPermission -> {
            Notice(
                "The global hotkey needs your permission: allow anydm under System Settings, Privacy & Security, Accessibility, " +
                    "then turn the global hotkey off and on again in Settings.",
            )
        }

        is HotkeyStatus.Unsupported -> {
            val advice =
                if (status.reason.contains("wayland", ignoreCase = true)) {
                    " On Linux under Wayland, apps can't listen for keys system-wide; use the clipboard watch or paste links instead."
                } else {
                    ""
                }
            Notice("The global hotkey can't work here: ${status.reason}.$advice")
        }

        is HotkeyStatus.Failed -> {
            Notice("The global hotkey should have worked and didn't: ${status.reason}")
        }

        else -> {
            null
        }
    }
}

/** What the status bar says about keep-awake, the after-queue action, the browser bridge and the hotkey. */
fun indicators(
    os: OsSignals,
    active: Int,
    now: Instant = Clock.System.now(),
): List<Indicator> =
    listOfNotNull(
        Indicator("status-awake", "Keeping awake").takeIf { os.keepAwake && active > 0 },
        afterQueueIndicator(os.afterQueue, now),
        Indicator(
            "status-bridge",
            when (val bridge = os.bridge) {
                BridgeStatus.Off -> "Browser bridge: off"
                is BridgeStatus.Listening -> "Browser bridge: port ${bridge.port}"
                is BridgeStatus.Failed -> "Browser bridge: unavailable (${bridge.reason})"
            },
        ),
        Indicator(
            "status-hotkey",
            when (os.hotkey) {
                HotkeyStatus.Active -> "Hotkey on"
                HotkeyStatus.NeedsPermission -> "Hotkey needs permission"
                is HotkeyStatus.Unsupported -> "Hotkey unsupported"
                is HotkeyStatus.Failed -> "Hotkey failed"
                HotkeyStatus.Off -> "Hotkey starting"
            },
        ).takeIf { os.hotkeyOn },
    )

private fun afterQueueIndicator(
    state: AfterQueueState,
    now: Instant,
): Indicator? {
    if (state.action == AfterQueueAction.NONE) return null
    val verb = if (state.action == AfterQueueAction.SLEEP) "sleep" else "shut down"
    val ends = state.countdownEndsAt
    val text =
        if (ends == null) {
            "After the queue: $verb"
        } else {
            val seconds = maxOf(0L, (ends - now).inWholeSeconds)
            "${if (state.action == AfterQueueAction.SLEEP) "Sleeping" else "Shutting down"} in $seconds s"
        }
    return Indicator("status-after-queue", text)
}
