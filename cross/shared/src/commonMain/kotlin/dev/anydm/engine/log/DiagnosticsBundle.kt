package dev.anydm.engine.log

import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.store.Settings
import dev.anydm.engine.store.SettingsStore
import kotlinx.io.files.Path
import kotlin.time.Clock

/** What the app is and where it runs, for the bundle's versions.txt; the caller knows it. */
data class AppInfo(
    val name: String,
    val version: String,
    val os: String,
    val runtime: String,
)

/** An external tool the app uses (yt-dlp, ffmpeg…); [version] null means it wasn't found. */
data class ToolVersion(
    val name: String,
    val version: String?,
    val path: String?,
)

/** One file of a bundle: its name inside the zip and its bytes. */
class BundleEntry(
    val name: String,
    val bytes: ByteArray,
)

/** What a diagnostics bundle needs from the platform: the log files, and a way to write a zip. */
interface DiagnosticsPlatform {
    /** The log files that exist, newest first: anydm.log, anydm.1.log, … */
    suspend fun logFiles(): List<BundleEntry>

    /** Writes [entries] as a zip at [target], through target.part and a move; replaces an existing target. */
    suspend fun writeZip(
        target: Path,
        entries: List<BundleEntry>,
    ): FileResult<Unit>
}

/**
 * A diagnostics bundle (DIA-04): the logs, the versions of the app and its tools, and the engine's settings, with
 * secrets removed. The logs are redacted again (a file may come from an older version), and settings are written from
 * an allowlist, so a setting added later appears only once someone adds it here on purpose.
 */
class DiagnosticsBundle(
    private val settings: SettingsStore,
    private val platform: DiagnosticsPlatform,
    private val clock: Clock,
    private val redactor: Redactor = Redactor(),
) {
    suspend fun export(
        target: Path,
        app: AppInfo,
        tools: List<ToolVersion> = emptyList(),
    ): FileResult<Unit> {
        val logs =
            platform.logFiles().map {
                BundleEntry(
                    "logs/${it.name}",
                    redactor.redact(it.bytes.decodeToString()).encodeToByteArray(),
                )
            }
        val entries =
            logs + BundleEntry("versions.txt", versions(app, tools).encodeToByteArray()) +
                BundleEntry("settings.txt", settingsText(settings.load()).encodeToByteArray())
        return platform.writeZip(target, entries)
    }

    private fun versions(
        app: AppInfo,
        tools: List<ToolVersion>,
    ): String =
        buildList {
            add("${app.name} ${app.version}")
            add("os ${app.os}")
            add("runtime ${app.runtime}")
            add("made ${logTimestamp(clock.now())}")
            if (tools.isEmpty()) add("no external tools yet")
            for (tool in tools) add(listOfNotNull("tool", tool.name, tool.version ?: "not found", tool.path).joinToString(" "))
        }.joinToString("\n", postfix = "\n")

    private fun settingsText(settings: Settings): String =
        listOf(
            "concurrencyLimit" to settings.concurrencyLimit,
            "defaultSaveFolder" to (settings.defaultSaveFolder ?: ""),
            "globalPause" to settings.globalPause,
            "maxRetries" to settings.maxRetries,
            "debugLogging" to settings.debugLogging,
        ).joinToString("\n", postfix = "\n") { (name, value) -> redactor.redact("$name = $value").trimEnd() }
}
