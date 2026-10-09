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

    /** A collection's videos, in listing order. */
    suspend fun entries(id: String): List<TaskDto>

    fun events(): Flow<ServerEvent>

    /** [allowDuplicate] adds a second copy of an address the list already holds, instead of a 409. */
    suspend fun addLink(
        url: String,
        preferred: String,
        allowDuplicate: Boolean = false,
    ): TaskDto

    /** One download, by id: a row the loaded page doesn't hold. */
    suspend fun task(id: String): TaskDto

    /** One collection (a playlist or channel row), by id. */
    suspend fun collection(id: String): TaskDto

    suspend fun addTorrent(
        torrent: String,
        files: List<Int> = emptyList(),
    ): TaskDto

    /** [collection] for a playlist row: a collection has its own routes. */
    suspend fun pause(
        id: String,
        collection: Boolean = false,
    ): TaskDto

    suspend fun resume(
        id: String,
        collection: Boolean = false,
    ): TaskDto

    suspend fun remove(
        id: String,
        deleteFiles: Boolean,
        collection: Boolean = false,
    )

    suspend fun stopSeeding(id: String)

    suspend fun bulk(action: String): Int
}
