package dev.anydm.engine.platform

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.GraphicsEnvironment
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import kotlin.time.Duration.Companion.seconds

/**
 * [Notifier] for the desktop (OSI-09). macOS is asked through `osascript`, Linux through `notify-send`; both are given the
 * text as separate arguments, never through a shell, and the text is escaped for AppleScript's strings. Windows has no
 * program to ask, so a balloon is shown from the system tray ([tray]). A machine without the program or the tray shows
 * nothing, and nothing fails.
 */
internal class DesktopNotifier(
    private val runner: ProcessRunner,
    private val os: DesktopOs,
    private val tray: (title: String, message: String) -> Unit = AwtTray::show,
) : Notifier {
    override suspend fun notify(
        title: String,
        message: String,
    ) {
        try {
            when (os) {
                DesktopOs.MACOS -> {
                    within {
                        runner.run(
                            ProcessSpec(
                                listOf("osascript", "-e", "display notification \"${quote(message)}\" with title \"${quote(title)}\""),
                            ),
                        )
                    }
                }

                // Most notification daemons read the text as markup: a file name must not turn into a link.
                DesktopOs.LINUX -> {
                    within { runner.run(ProcessSpec(listOf("notify-send", "--app-name=anydm", "--", markup(title), markup(message)))) }
                }

                DesktopOs.WINDOWS -> {
                    tray(title, message)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The user not being told is not worth a failure.
        }
    }

    // A program that hangs must not hold the caller: the user not being told is the smaller loss.
    private suspend fun within(block: suspend () -> Unit) {
        withTimeoutOrNull(RUN_LIMIT) { block() }
    }

    private fun markup(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // Inside an AppleScript string only the backslash and the double quote mean anything.
    private fun quote(text: String): String = text.replace("\\", "\\\\").replace("\"", "\\\"")
}

private val RUN_LIMIT = 5.seconds

/** The notifier of the OS this JVM runs on (OSI-09); the desktop passes it to the notification relay. */
fun notifierForCurrentOs(runner: ProcessRunner): Notifier = DesktopNotifier(runner, DesktopOs.of(System.getProperty("os.name")))

/** The system tray's balloon, for Windows. It uses the tray icon the app has, and adds a plain one the first time only when there is none. */
internal object AwtTray {
    private var icon: TrayIcon? = null

    @Synchronized
    fun show(
        title: String,
        message: String,
    ) {
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) return
        // The app's own tray icon, when it has one, carries the balloon: a second, blank icon beside it would be a stray.
        val shown =
            SystemTray.getSystemTray().trayIcons.firstOrNull() ?: icon
                ?: TrayIcon(BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), "anydm").also {
                    it.isImageAutoSize = true
                    SystemTray.getSystemTray().add(it)
                    icon = it
                }
        shown.displayMessage(title, message, TrayIcon.MessageType.INFO)
    }
}
