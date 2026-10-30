package dev.anydm.desktop

import dev.anydm.engine.DownloadSnapshot
import dev.anydm.engine.EngineEvent
import dev.anydm.engine.EngineState
import dev.anydm.engine.SpeedWindow
import dev.anydm.engine.core.CategoryId
import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.PauseReason
import dev.anydm.engine.files.Category
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** An [EngineSource] the tests drive by hand. */
internal class FakeSource(
    var categoryList: List<Category> = emptyList(),
    var startError: Throwable? = null,
) : EngineSource {
    override val state = MutableStateFlow<EngineState>(EngineState.NotStarted)
    override val downloads = MutableStateFlow<List<DownloadSnapshot>>(emptyList())
    override val totalSpeed = MutableStateFlow(SpeedWindow.Empty)
    override val events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64)
    var started = 0
    var stopped = 0

    override suspend fun categories(): List<Category> = categoryList

    override suspend fun start() {
        started++
        startError?.let { throw it }
        state.value = EngineState.Running
    }

    override suspend fun stop() {
        stopped++
        state.value = EngineState.Stopped
    }
}

internal fun snapshot(
    id: Long,
    status: DownloadStatus,
    category: CategoryId? = null,
    pauseReasons: Set<PauseReason> = emptySet(),
): DownloadSnapshot =
    DownloadSnapshot(
        download = Download(DownloadId(id), DownloadKind.DIRECT, status, categoryId = category, pauseReasons = pauseReasons),
        downloaded = 0,
        total = null,
        speed = 0,
        speedSamples = emptyList(),
        lastFailure = null,
        attempts = emptyList(),
        mirrors = emptyList(),
        files = emptyList(),
    )
