package dev.anydm.store

import dev.anydm.model.EntryCounts
import dev.anydm.model.Position
import dev.anydm.model.Task
import dev.anydm.model.TaskDto
import dev.anydm.model.TaskKind
import dev.anydm.model.TaskStatus
import dev.anydm.model.toTask

/** A task as a test needs it: an id, a status, and whatever else is named. */
fun task(
    id: String,
    status: TaskStatus = TaskStatus.DOWNLOADING,
    kind: TaskKind = TaskKind.FILE,
    title: String = id,
    parentId: String? = null,
    progress: Int = 0,
    totalBytes: Long = 0,
    error: String? = null,
    errorCode: String? = null,
    attempts: Int = 0,
    maxAttempts: Int? = null,
    entryCounts: EntryCounts? = null,
    positions: List<Position>? = null,
): Task =
    TaskDto(id = id, kind = kind.wire, status = status.wire, title = title)
        .toTask()
        .copy(
            parentId = parentId,
            progress = progress,
            totalBytes = totalBytes,
            error = error,
            errorCode = errorCode,
            attempts = attempts,
            maxAttempts = maxAttempts,
            entryCounts = entryCounts,
            positions = positions,
        )

/** A task's DTO, as a fake API answers with it. */
fun dto(
    id: String,
    status: TaskStatus = TaskStatus.DOWNLOADING,
): TaskDto = TaskDto(id = id, kind = "file", status = status.wire, title = id)
