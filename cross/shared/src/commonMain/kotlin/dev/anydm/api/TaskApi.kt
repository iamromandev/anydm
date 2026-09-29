package dev.anydm.api

import dev.anydm.model.SummaryDto
import dev.anydm.model.TaskDto
import kotlinx.coroutines.flow.Flow

/** What `TaskStore` asks of the API. `AnydmApi` implements it; tests fake it. */
interface TaskApi {
    suspend fun listTasks(
        page: Int,
        pageSize: Int,
        group: String = "all",
        sort: String = "-created_at",
    ): Page<TaskDto>

    suspend fun summary(): SummaryDto

    /** A group's videos, in playlist order. */
    suspend fun entries(id: String): List<TaskDto>

    fun events(): Flow<ServerEvent>

    suspend fun addLink(
        url: String,
        preferred: String,
    ): TaskDto

    suspend fun addTorrent(
        torrent: String,
        files: List<Int> = emptyList(),
    ): TaskDto

    suspend fun pause(id: String): TaskDto

    suspend fun resume(id: String): TaskDto

    suspend fun remove(
        id: String,
        deleteFiles: Boolean,
    )

    suspend fun stopSeeding(id: String)

    suspend fun bulk(action: String): Int
}
