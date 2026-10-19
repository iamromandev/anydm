package dev.anydm.engine.core

import dev.anydm.engine.files.Profile

// A job's options, read live (SET-02): what the job itself says wins, then its profile's, then the global default,
// which the callers apply when these answer null. A profile that is gone is passed as null.

fun effectiveFormat(
    jobFormat: Format?,
    profile: Profile?,
): Format? = jobFormat ?: profile?.format

fun effectiveSpeedLimit(
    job: Download,
    profile: Profile?,
): Long? = job.speedLimit ?: profile?.speedLimit

fun effectiveCategory(
    job: Download,
    profile: Profile?,
): CategoryId? = job.categoryId ?: profile?.categoryId

fun effectiveSaveFolder(
    job: Download,
    profile: Profile?,
): String? = job.saveFolder ?: profile?.saveFolder

/** A job has no template of its own; the profile's beats its category's. */
fun effectiveTemplate(
    profile: Profile?,
    categoryTemplate: String?,
): String? = profile?.nameTemplate ?: categoryTemplate
