package dev.anydm.engine.core

import kotlin.jvm.JvmInline

// One id type per entity, so one kind of id can't be passed as another. All are assigned by the
// store (#549) as SQLite row ids. CategoryId is FIL-01, ProfileId is SET-02.

@JvmInline
value class DownloadId(
    val value: Long,
)

@JvmInline
value class FileId(
    val value: Long,
)

@JvmInline
value class SegmentId(
    val value: Long,
)

@JvmInline
value class MirrorId(
    val value: Long,
)

@JvmInline
value class AttemptId(
    val value: Long,
)

@JvmInline
value class CategoryId(
    val value: Long,
)

@JvmInline
value class ProfileId(
    val value: Long,
)

@JvmInline
value class ScheduleRuleId(
    val value: Long,
)

/** A saved proxy (NET-15). */
@JvmInline
value class ProxyId(
    val value: Long,
)
