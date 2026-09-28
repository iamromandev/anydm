package dev.anydm.store

import dev.anydm.api.ApiException
import dev.anydm.api.Page
import dev.anydm.api.ServerEvent
import dev.anydm.api.TaskApi
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
    var affected = 0

    override suspend fun listTasks(
        page: Int,
        pageSize: Int,
        group: String,
        sort: String,
    ): Page<TaskDto> {
        listCalls += page to group
        pageGate?.await()
        return pages[page] ?: Page(emptyList(), PageMetaDto(page = page, totalPages = 1))
    }

    override suspend fun summary(): SummaryDto = summaryDto

    override fun events(): Flow<ServerEvent> =
        flow {
            connects += clock()
            stream(connects.size - 1)
        }

    private fun answerOrFail(): TaskDto = failWith?.let { throw it } ?: answer

    override suspend fun addLink(
        url: String,
        preferred: String,
    ) = answerOrFail()

    override suspend fun addTorrent(
        torrent: String,
        files: List<Int>,
    ) = answerOrFail()

    override suspend fun pause(id: String) = answerOrFail()

    override suspend fun resume(id: String) = answerOrFail()

    override suspend fun remove(
        id: String,
        deleteFiles: Boolean,
    ) {
        removed += id
        failWith?.let { throw it }
    }

    override suspend fun stopSeeding(id: String) {
        failWith?.let { throw it }
    }

    override suspend fun bulk(action: String): Int = affected
}
