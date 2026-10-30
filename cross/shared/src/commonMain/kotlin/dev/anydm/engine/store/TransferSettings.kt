package dev.anydm.engine.store

import dev.anydm.engine.core.Pacing
import dev.anydm.engine.core.StepRunner
import dev.anydm.engine.files.ClashPolicy
import dev.anydm.engine.net.AfterQueueAction
import dev.anydm.engine.net.SlowMode
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable

/**
 * The transfer and file part of [Settings] (#608): what the Settings screen edits, what [dev.anydm.engine.Engine.applyTransferSettings]
 * takes and what an export carries. Network, OS and tool settings are not in it, so changing these can't touch them.
 * The defaults are those of [Settings].
 */
@Serializable
data class TransferSettings(
    val defaultSaveFolder: String? = null,
    val clashPolicy: ClashPolicy = ClashPolicy.RENAME,
    val freeSpaceReserve: Long = StepRunner.DEFAULT_FREE_SPACE_RESERVE,
    val playlistFile: Boolean = true,
    val maxRetries: Int = 5,
    val historyLimit: Int = 1000,
    val pacing: Pacing = Pacing(),
    val connectionsPerDownload: Int = 4,
    val globalSpeedLimit: Long? = null,
    val globalUploadLimit: Long? = null,
    val slowMode: Boolean = false,
    val slowModeLimit: Long = SlowMode.DEFAULT_LIMIT,
    val seedRatio: Double? = null,
    val seedMinutes: Int? = null,
    val concurrencyLimit: Int = 3,
    val globalPause: Boolean = false,
    val keepAwake: Boolean = true,
    val afterQueue: AfterQueueAction = AfterQueueAction.NONE,
) {
    /** Why the engine would refuse these values; empty when it takes them. */
    fun problems(): List<String> =
        try {
            Settings().with(this)
            emptyList()
        } catch (e: IllegalArgumentException) {
            listOf(e.message ?: "not a valid setting")
        }
}

fun Settings.transfer(): TransferSettings =
    TransferSettings(
        defaultSaveFolder = defaultSaveFolder?.toString(),
        clashPolicy = clashPolicy,
        freeSpaceReserve = freeSpaceReserve,
        playlistFile = playlistFile,
        maxRetries = maxRetries,
        historyLimit = historyLimit,
        pacing = pacing,
        connectionsPerDownload = connectionsPerDownload,
        globalSpeedLimit = globalSpeedLimit,
        globalUploadLimit = globalUploadLimit,
        slowMode = slowMode,
        slowModeLimit = slowModeLimit,
        seedRatio = seedRatio,
        seedMinutes = seedMinutes,
        concurrencyLimit = concurrencyLimit,
        globalPause = globalPause,
        keepAwake = keepAwake,
        afterQueue = afterQueue,
    )

/** These settings with the transfer part taken from [transfer]; throws IllegalArgumentException where [Settings] refuses a value. */
fun Settings.with(transfer: TransferSettings): Settings =
    copy(
        defaultSaveFolder = transfer.defaultSaveFolder?.let(::Path),
        clashPolicy = transfer.clashPolicy,
        freeSpaceReserve = transfer.freeSpaceReserve,
        playlistFile = transfer.playlistFile,
        maxRetries = transfer.maxRetries,
        historyLimit = transfer.historyLimit,
        pacing = transfer.pacing,
        connectionsPerDownload = transfer.connectionsPerDownload,
        globalSpeedLimit = transfer.globalSpeedLimit,
        globalUploadLimit = transfer.globalUploadLimit,
        slowMode = transfer.slowMode,
        slowModeLimit = transfer.slowModeLimit,
        seedRatio = transfer.seedRatio,
        seedMinutes = transfer.seedMinutes,
        concurrencyLimit = transfer.concurrencyLimit,
        globalPause = transfer.globalPause,
        keepAwake = transfer.keepAwake,
        afterQueue = transfer.afterQueue,
    )
