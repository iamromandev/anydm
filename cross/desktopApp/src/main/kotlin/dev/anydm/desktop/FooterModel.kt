package dev.anydm.desktop

import dev.anydm.engine.queue.CommandResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.Path

internal const val FREE_SPACE_POLL_MS = 30_000L

data class FooterState(
    /** Bytes free where downloads are saved; null when that can't be measured. */
    val freeSpace: Long? = null,
    val slowMode: Boolean = false,
    /** Bytes per second Slow mode holds downloads to. */
    val slowModeLimit: Long = 0,
    val unit: SpeedUnit = SpeedUnit.BYTES,
    val theme: ThemeChoice = ThemeChoice.DARK,
    val message: String? = null,
)

/** The footer: room left to save to, Slow mode, the speed unit and the theme; the last two are remembered in [prefs]. */
class FooterModel(
    private val source: EngineSource,
    private val prefs: ViewPrefs,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(FooterState(unit = prefs.speedUnit, theme = prefs.theme))
    val state: StateFlow<FooterState> = mutable.asStateFlow()

    init {
        scope.launch {
            while (isActive) {
                refresh()
                delay(FREE_SPACE_POLL_MS)
            }
        }
    }

    /** Reads the engine's Slow mode and measures the free space again. */
    suspend fun refresh() {
        try {
            val settings = source.transferSettings()
            val folder = settings.defaultSaveFolder ?: Path.of(System.getProperty("user.home"), "Downloads").toString()
            mutable.update {
                it.copy(freeSpace = source.freeSpace(folder), slowMode = settings.slowMode, slowModeLimit = settings.slowModeLimit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IllegalStateException) {
            // A stopped engine has nothing to show; the footer keeps what it had.
        }
    }

    fun toggleSlowMode() {
        scope.launch {
            try {
                var now = false
                // Under the settings lock: a Limits save made meanwhile is not written over with what was read before it.
                val result = source.updateTransfer { settings -> settings.copy(slowMode = !settings.slowMode).also { now = it.slowMode } }
                if (result is CommandResult.Done) {
                    mutable.update { it.copy(slowMode = now, message = null) }
                } else {
                    mutable.update { it.copy(message = "Slow mode could not be changed: ${reason(result)}") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: IllegalStateException) {
                mutable.update { it.copy(message = "Slow mode could not be changed: the engine has stopped.") }
            }
        }
    }

    fun setUnit(unit: SpeedUnit) {
        prefs.speedUnit = unit
        mutable.update { it.copy(unit = unit) }
    }

    fun toggleTheme() {
        val next = if (mutable.value.theme == ThemeChoice.DARK) ThemeChoice.LIGHT else ThemeChoice.DARK
        prefs.theme = next
        mutable.update { it.copy(theme = next) }
    }

    fun dismissMessage() = mutable.update { it.copy(message = null) }

    private fun reason(result: CommandResult) = (result as? CommandResult.Invalid)?.reason ?: "the engine refused it."
}
