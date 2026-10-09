package dev.anydm.store

import dev.anydm.api.ApiException
import dev.anydm.api.Page
import dev.anydm.api.ServerEvent
import dev.anydm.api.TaskApi
import dev.anydm.model.CategoryRefDto
import dev.anydm.model.PageMetaDto
import dev.anydm.model.SummaryDto
import dev.anydm.model.TaskDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

/** A scripted API: pages to answer with, a stream per connection, and what each action returns. */
class FakeApi : TaskApi {
    val pages = mutableMapOf<Int, Page<TaskDto>>()
    var pageGate: CompletableDeferred<Unit>? = null
    val listCalls = mutableListOf<Pair<Int, String>>()
    var summaryDto = SummaryDto(all = 1)
    var clock: () -> Long = { 0 }
    val connects = mutableListOf<Long>()

    /** What connection number n (from 0) does. The default fails at once, like a server that's down. */
    var stream: suspend FlowCollector<ServerEvent>.(Int) -> Unit = { error("down") }
    var answer: TaskDto = TaskDto(id = "x")
    var failWith: ApiException? = null
    val removed = mutableListOf<String>()

    /** Ids an action was sent to by the collection's routes. */
    val viaCollection = mutableListOf<String>()
    var affected = 0

    /** The `category` each list call sent, beside `listCalls`. */
    val categoryFilters = mutableListOf<String?>()
    val summaryFilters = mutableListOf<String?>()
    val addedCategories = mutableListOf<String?>()
    val moved = mutableListOf<Triple<String, Boolean, String>>()
    var movedAnswer: TaskDto? = null

    override suspend fun listTasks(
        page: Int,
        pageSize: Int,
        group: String,
        sort: String,
        categoryFilter: String?,
    ): Page<TaskDto> {
        listCalls += page to group
        categoryFilters += categoryFilter
        pageGate?.await()
        return pages[page] ?: Page(emptyList(), PageMetaDto(page = page, totalPages = 1))
    }

    var summaryCalls = 0

    override suspend fun summary(categoryFilter: String?): SummaryDto {
        summaryCalls += 1
        summaryFilters += categoryFilter
        return summaryDto
    }

    override fun events(): Flow<ServerEvent> =
        flow {
            connects += clock()
            stream(connects.size - 1)
        }

    private fun answerOrFail(): TaskDto = failWith?.let { throw it } ?: answer

    /** Whether each addLink asked for a second copy. A second copy is never refused. */
    val allowed = mutableListOf<Boolean>()

    override suspend fun addLink(
        url: String,
        preferred: String,
        allowDuplicate: Boolean,
        categoryId: String?,
    ): TaskDto {
        allowed += allowDuplicate
        addedCategories += categoryId
        return if (allowDuplicate) answer else answerOrFail()
    }

    /** Rows [task] can fetch by id. */
    val rows = mutableMapOf<String, TaskDto>()
    val taskCalls = mutableListOf<String>()
    val collectionCalls = mutableListOf<String>()

    override suspend fun collection(id: String): TaskDto {
        collectionCalls += id
        return rows[id] ?: throw ApiException("No such collection", 404, null)
    }

    override suspend fun task(id: String): TaskDto {
        taskCalls += id
        return rows[id] ?: throw ApiException("No such download", 404, null)
    }

    override suspend fun addTorrent(
        torrent: String,
        files: List<Int>,
        categoryId: String?,
    ): TaskDto {
        addedCategories += categoryId
        return answerOrFail()
    }

    override suspend fun moveToCategory(
        id: String,
        collection: Boolean,
        categoryId: String,
    ): TaskDto {
        moved += Triple(id, collection, categoryId)
        failWith?.let { throw it }
        return movedAnswer ?: TaskDto(id = id, category = CategoryRefDto(categoryId, "Moved"))
    }

    override suspend fun pause(
        id: String,
        collection: Boolean,
    ): TaskDto {
        if (collection) viaCollection += id
        return answerOrFail()
    }

    override suspend fun resume(
        id: String,
        collection: Boolean,
    ): TaskDto {
        if (collection) viaCollection += id
        return answerOrFail()
    }

    override suspend fun remove(
        id: String,
        deleteFiles: Boolean,
        collection: Boolean,
    ) {
        if (collection) viaCollection += id
        removed += id
        failWith?.let { throw it }
    }

    override suspend fun stopSeeding(id: String) {
        failWith?.let { throw it }
    }

    override suspend fun bulk(action: String): Int = affected

    val entries = mutableMapOf<String, List<TaskDto>>()
    val entryCalls = mutableListOf<String>()

    override suspend fun entries(id: String): List<TaskDto> {
        entryCalls += id
        return entries[id].orEmpty()
    }
}
