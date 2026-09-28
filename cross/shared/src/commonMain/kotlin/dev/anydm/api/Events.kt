package dev.anydm.api

import dev.anydm.model.AnydmJson
import dev.anydm.model.DiskDto
import dev.anydm.model.ProgressDto
import dev.anydm.model.Task
import dev.anydm.model.TaskDto
import dev.anydm.model.toTask
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.utils.io.readLine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** What `/download/events` says, typed. */
sealed interface ServerEvent {
    /** The whole list, sent first on every connection. */
    data class Snapshot(
        val tasks: List<Task>,
    ) : ServerEvent

    data class TaskChanged(
        val task: Task,
    ) : ServerEvent

    /** Only the numbers that moved; a group's video carries `parentId`. */
    data class Progress(
        val progress: ProgressDto,
    ) : ServerEvent

    data class Disk(
        val disk: DiskDto,
    ) : ServerEvent
}

/** A frame as an event, or `null` for one this build doesn't know. */
fun toServerEvent(frame: SseFrame): ServerEvent? =
    when (frame.event) {
        "tasks" -> ServerEvent.Snapshot(AnydmJson.decodeFromString<List<TaskDto>>(frame.data).map { it.toTask() })
        "task" -> ServerEvent.TaskChanged(AnydmJson.decodeFromString<TaskDto>(frame.data).toTask())
        "progress" -> ServerEvent.Progress(AnydmJson.decodeFromString<ProgressDto>(frame.data))
        "disk" -> ServerEvent.Disk(AnydmJson.decodeFromString<DiskDto>(frame.data))
        else -> null
    }

/**
 * The live stream, until the server closes it. A refusal is thrown as the API's own error
 * ([Unauthorized] for a bad key). Reconnecting is the caller's job: the store in part 2.
 */
internal fun AnydmApi.openEvents(): Flow<ServerEvent> =
    flow {
        val url = URLBuilder(baseUrl).apply { appendPathSegments("download", "events") }.buildString()
        http
            .prepareGet(url) {
                apiKey?.let { header(API_KEY_HEADER, it) }
                header(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
            }.execute { response ->
                if (response.status.value >= 400) unwrap(response.status.value, response.bodyAsText())
                val channel = response.bodyAsChannel()
                val parser = SseParser()
                while (true) {
                    val line = channel.readLine() ?: break
                    val frame = parser.feed(line) ?: continue
                    toServerEvent(frame)?.let { emit(it) }
                }
            }
    }
